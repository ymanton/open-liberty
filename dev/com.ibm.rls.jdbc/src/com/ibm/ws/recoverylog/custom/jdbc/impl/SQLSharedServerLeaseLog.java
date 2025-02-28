/*******************************************************************************
 * Copyright (c) 2014,2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.recoverylog.custom.jdbc.impl;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.StringTokenizer;

import javax.resource.spi.ResourceAllocationException;
import javax.sql.DataSource;

import com.ibm.tx.config.ConfigurationProviderManager;
import com.ibm.tx.util.Utils;
import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.recoverylog.spi.CustomLogProperties;
import com.ibm.ws.recoverylog.spi.InternalLogException;
import com.ibm.ws.recoverylog.spi.LeaseInfo;
import com.ibm.ws.recoverylog.spi.PeerLeaseData;
import com.ibm.ws.recoverylog.spi.PeerLeaseTable;
import com.ibm.ws.recoverylog.spi.RecoveryFailedException;
import com.ibm.ws.recoverylog.spi.SharedServerLeaseLog;
import com.ibm.ws.recoverylog.spi.TraceConstants;

/**
 *
 */
public class SQLSharedServerLeaseLog implements SharedServerLeaseLog, SQLRetriableLog {

    /**
     * WebSphere RAS TraceComponent registration.
     */
    private static final TraceComponent tc = Tr.register(SQLSharedServerLeaseLog.class,
                                                         TraceConstants.TRACE_GROUP, TraceConstants.NLS_FILE);
    // Reference to the dedicated non-transactional datasource
    private DataSource _theDS;

    /**
     * A reference to the LogProperties object that defines the identity and physical
     * location of the recovery log.
     */
    private final CustomLogProperties _customLogProperties;

    /**
     * Flag whether the database type has ever been determined
     */
    boolean _determineDBType = false;
    /**
     * Which RDBMS are we working against?
     */
    volatile private boolean _isOracle;
    volatile private boolean _isPostgreSQL;
    volatile private boolean _isDB2;
    volatile private boolean _isSQLServer;
    volatile private boolean _isDerby;
    volatile private boolean _isNonStandard;

    private boolean _sqlTransientErrorHandlingEnabled = true;
    private boolean _logRetriesEnabled = false;
    private int _leaseTimeout;
    private final String _leaseTableName = "WAS_LEASES_LOG";

    /**
     * These strings are used for Database table creation. DDL is
     * different for DB2, MS SQL Server, PostgreSQL and Oracle.
     */
    private final String genericTablePreString = "CREATE TABLE ";
    private final String genericTablePostString = "( SERVER_IDENTITY VARCHAR(128), RECOVERY_GROUP VARCHAR(128), LEASE_OWNER VARCHAR(128), " +
                                                  "LEASE_TIME BIGINT) ";

    private final String oracleTablePreString = "CREATE TABLE ";
    private final String oracleTablePostString = "( SERVER_IDENTITY VARCHAR(128), RECOVERY_GROUP VARCHAR(128), LEASE_OWNER VARCHAR(128), " +
                                                 "LEASE_TIME NUMBER(19)) ";

    private final String postgreSQLTablePreString = "CREATE TABLE ";
    private final String postgreSQLTablePostString = "( SERVER_IDENTITY VARCHAR (128) UNIQUE NOT NULL, RECOVERY_GROUP VARCHAR (128) NOT NULL, LEASE_OWNER VARCHAR (128) NOT NULL, "
                                                     +
                                                     "LEASE_TIME BIGINT);";

    /**
     * We only want one client at a time to attempt to create a new
     * Database table.
     */
    private static final Object _CreateTableLock = new Object();

    /**
     * Flag to indicate whether the server is stopping.
     */
    volatile private boolean _serverStopping;

    private ResultSet _updatelockingRS = null;
    private Statement _lockingStmt = null;
    private Statement _deleteStmt = null;
    private Statement _peerLockingStmt = null;
    private ResultSet _peerLockingRS = null;
    private Statement _claimPeerlockingStmt = null;
    private PreparedStatement _claimPeerUpdateStmt = null;
    private ResultSet _claimPeerLockingRS = null;

    public SQLSharedServerLeaseLog(CustomLogProperties logProperties) {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "SQLSharedServerStatusLog", new Object[] { logProperties, this });

        // Cache the supplied information
        _customLogProperties = logProperties;

        if (tc.isEntryEnabled())
            Tr.exit(tc, "SQLSharedServerStatusLog", this);
    }

    @Override
    public synchronized void getLeasesForPeers(final PeerLeaseTable peerLeaseTable, String recoveryGroup) throws Exception {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "getLeasesForPeers", new java.lang.Object[] { recoveryGroup, this });

        boolean getPeerSuccess = false;
        // For exception handling
        Throwable nonTransientException = null;
        SQLException currentSqlEx = null;

        // if the server is stopping, we should simply return
        if (_serverStopping) {
            if (tc.isEntryEnabled())
                Tr.exit(tc, "getLeasesForPeers", "server stopping");
            return;
        }

        // The Database Connection
        Connection conn = null;

        // We obtain a connection to the underlying DB
        try {
            // Get a connection to the DB
            conn = getConnection();

            // If we were unable to get a connection, throw an exception
            if (conn == null) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "getLeasesForPeers", "Null connection InternalLogException");
                throw new InternalLogException("Failed to get JDBC Connection", null);
            }

            if (tc.isDebugEnabled())
                Tr.debug(tc, "Set autocommit FALSE on the connection");
            conn.setAutoCommit(false);

            getPeerLeasesFromTable(peerLeaseTable, recoveryGroup, conn);

            // commit and return
            conn.commit();
            getPeerSuccess = true;
        }
        // Catch and report an SQLException. In the finally block we'll determine whether the condition is transient or not.
        catch (SQLException sqlex) {
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Lease delete failed with exception: " + sqlex);
            Tr.audit(tc, "WTRN0107W: " +
                         "Caught SQLException when retrieving peer leases, exc: " + sqlex);
            // Set the exception that will be reported
            currentSqlEx = sqlex;
        } catch (Throwable exc) {
            Tr.audit(tc, "WTRN0107W: " +
                         "Caught non-SQLException Throwable when retrieving peer leases, exc: " + exc);
            nonTransientException = exc;
        } finally {

            if (!getPeerSuccess) {
                // Tidy up current connection before dropping into retry code.
                // If it fails, trace the failure but allow processing to continue
                try {
                    if (_peerLockingRS != null && !_peerLockingRS.isClosed())
                        _peerLockingRS.close();
                    if (_peerLockingStmt != null && !_peerLockingStmt.isClosed())
                        _peerLockingStmt.close();
                    if (conn != null)
                        conn.close();
                } catch (Throwable exc) {
                    // Trace the exception
                    if (tc.isDebugEnabled())
                        Tr.debug(tc, "Tidy up Failed, after lease retrieval failure, got exception: " + exc);
                }

                // if the server is stopping, we should simply return without driving any retry logic
                if (_serverStopping) {
                    if (tc.isEntryEnabled())
                        Tr.exit(tc, "getLeasesForPeers", "server stopping");
                    return;
                }

                boolean failAndReport = true;
                // If currentSqlEx is non-null, then we potentially have a condition that may be retried. If it is not null, then
                // the nonTransientException will have been set.
                if (currentSqlEx != null) {
                    // Set the exception that will be reported
                    nonTransientException = currentSqlEx;
                    GetPeerLeaseRetry getPeerLeaseRetry = new GetPeerLeaseRetry(peerLeaseTable, recoveryGroup);
                    getPeerLeaseRetry.setNonTransientException(currentSqlEx);
                    // The following method will reset "nonTransientException" if it cannot recover
                    if (_sqlTransientErrorHandlingEnabled) {
                        failAndReport = getPeerLeaseRetry.retryAfterSQLException(this, currentSqlEx);

                        if (failAndReport)
                            nonTransientException = getPeerLeaseRetry.getNonTransientException();
                    }
                }

                // We've been through the while loop
                if (failAndReport) {
                    Tr.audit(tc, "WTRN0100E: " +
                                 "Cannot recover from SQLException when retrieving server leases for recoverygroup " + recoveryGroup + " Exception: "
                                 + nonTransientException);

                    if (tc.isEntryEnabled())
                        Tr.exit(tc, "getLeasesForPeers", "InternalLogException");
                    throw new InternalLogException(nonTransientException);

                } else {
                    Tr.audit(tc, "WTRN0108I: Have recovered from SQLException when retrieving server leases for recoverygroup " + recoveryGroup);
                }
            }

            if (_peerLockingRS != null && !_peerLockingRS.isClosed())
                _peerLockingRS.close();
            if (_peerLockingStmt != null && !_peerLockingStmt.isClosed())
                _peerLockingStmt.close();
            if (conn != null)
                conn.close();
        }

        if (tc.isEntryEnabled())
            Tr.exit(tc, "getLeasesForPeers");
    }

    private void getPeerLeasesFromTable(final PeerLeaseTable peerLeaseTable, String recoveryGroup, Connection conn) throws Exception {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "getPeerLeasesFromTable", new java.lang.Object[] { recoveryGroup, conn, this });

        // reset the table
        peerLeaseTable.clear();

        // lookup peers and their timestamps in the leases table.
        if (tc.isDebugEnabled())
            Tr.debug(tc, "create a statement");
        _peerLockingStmt = conn.createStatement();
        // If we were unable to get a connection, throw an exception

        // Set a null recovery group to empty string
        if (recoveryGroup == null) {
            recoveryGroup = "";
        }
        boolean newTable = true;

        // Get peers from table
        // Test if they are stale
        // If they are we'll need to recover them
        // If not just return
        // if needed to create new table then just return

        synchronized (_CreateTableLock) // Guard against trying to create a table from multiple threads
        {
            try {
                // TODO: Use RDBMS SELECT FOR UPDATE to lock table for recovery
                String queryString = "SELECT SERVER_IDENTITY, LEASE_TIME" +
                                     " FROM " + _leaseTableName + " WHERE RECOVERY_GROUP = '" + recoveryGroup + "'";
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Attempt to select from the lease table - " + queryString);
                _peerLockingRS = _peerLockingStmt.executeQuery(queryString);

                newTable = false;
            } catch (Exception e) {
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Query failed with exception: " + e);

                try {
                    // Perhaps we couldn't find the table ... so attempt to create it
                    createLeaseTable(conn);

                    conn.commit();

                    newTable = true;
                } catch (Exception ine) {
                    if (tc.isDebugEnabled())
                        Tr.debug(tc, "Table Creation failed with exception: " + ine);
                    // Set the current exception to ine
                    throw ine;
                }

            } // eof Exception e block

        } // eof synchronize block

        // If table creation succeeded then we are done.
        if (newTable) {
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Table Creation succeeded");
        } else {
//TODO?HEY THIS
            // Now process through the peers we need to handle
            while (_peerLockingRS.next()) {
                final String recoveryId = _peerLockingRS.getString(1);
                final long leaseTime = _peerLockingRS.getLong(2);

                if (tc.isEventEnabled()) {
                    Tr.event(tc, "Lease Table: read recoveryId: " + recoveryId);
                    Tr.event(tc, "Lease Table: read leaseTime: " + Utils.traceTime(leaseTime));
                }

                PeerLeaseData pld = new PeerLeaseData(recoveryId, leaseTime, _leaseTimeout);

                peerLeaseTable.addPeerEntry(pld);
            }
        }

        if (tc.isEntryEnabled())
            Tr.exit(tc, "getPeerLeasesFromTable");
    }

    /**
     * Will update the lease for an existing server in the table. Or insert a new row if required.
     *
     * @param recoveryIdentity
     * @param leaseTime
     * @throws Exception
     */
    @Override
    public synchronized void updateServerLease(String recoveryIdentity, String recoveryGroup, boolean isServerStartup) throws Exception {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "updateServerLease", new java.lang.Object[] { recoveryIdentity, recoveryGroup, isServerStartup, this });
        boolean updateSuccess = false;
        Connection conn = null;
        _updatelockingRS = null;

        // For exception handling
        Throwable nonTransientException = null;
        SQLException currentSqlEx = null;
        // if the server is stopping, we should simply return
        if (_serverStopping) {
            if (tc.isEntryEnabled())
                Tr.exit(tc, "updateServerLease", "server stopping");
            return;
        }

        if (tc.isDebugEnabled())
            Tr.debug(tc, "Work with recoveryIdentity - ", recoveryIdentity);

        // Reset a null recoveryGroup to an empty string
        if (recoveryGroup == null)
            recoveryGroup = "";

        try {
            // Get a connection to the DB
            conn = getConnection();

            // If we were unable to get a connection, throw an exception, but not if we're stopping
            if (conn == null) {
                if (!_serverStopping) {
                    if (tc.isEntryEnabled())
                        Tr.exit(tc, "updateServerLease", "Null connection InternalLogException");
                    throw new InternalLogException("Failed to get JDBC Connection", null);
                } else {
                    if (tc.isEntryEnabled())
                        Tr.exit(tc, "updateServerLease", "null connection");
                    return;
                }
            }

            if (tc.isDebugEnabled())
                Tr.debug(tc, "Set autocommit FALSE on the connection");
            conn.setAutoCommit(false);

            // We can go ahead and query the Database
            boolean newTable = queryLeaseTable(recoveryIdentity, conn, isServerStartup);
            // if the server is stopping, we should simply return
            if (_serverStopping) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "updateServerLease", this);
                return;
            }

            boolean needInsert = true;
            if (!newTable)
                needInsert = updateLeaseTable(recoveryIdentity, recoveryGroup, conn, isServerStartup);

            // Either a new table or we couldn't find the row for our server. Insert it.
            if (needInsert) {
                if (!_serverStopping) {
                    // Insert a new row into the lease table
                    insertNewLease(recoveryIdentity, recoveryGroup, conn);
                } else { // server is stopping exit without insert
                    if (tc.isEntryEnabled())
                        Tr.exit(tc, "updateServerLease", "skip insert server is stopping");
                    return;
                }
            }

            if (tc.isDebugEnabled())
                Tr.debug(tc, "COMMIT the change");
            conn.commit();
            updateSuccess = true;
        }
        // Catch and report an SQLException. In the finally block we'll determine whether the condition is transient or not.
        catch (SQLException sqlex) {
            Tr.audit(tc, "WTRN0107W: " +
                         "Caught SQLException when updating server lease: " + sqlex);
            // Set the exception that will be reported
            currentSqlEx = sqlex;
        } catch (Throwable exc) {
            Tr.audit(tc, "WTRN0107W: " +
                         "Caught non-SQLException Throwable when updating server lease: " + exc);
            nonTransientException = exc;
        } finally {

            if (!updateSuccess) {
                // Tidy up current connection before dropping into retry code.
                // If it fails, trace the failure but allow processing to continue
                try {
                    if (_updatelockingRS != null && !_updatelockingRS.isClosed())
                        _updatelockingRS.close();
                    if (_lockingStmt != null && !_lockingStmt.isClosed())
                        _lockingStmt.close();
                    if (conn != null)
                        conn.close();
                } catch (Throwable exc) {
                    // Trace the exception
                    if (tc.isDebugEnabled())
                        Tr.debug(tc, "Tidy up Failed, after lease update failure, got exception: " + exc);
                }

                // if the server is stopping, we should simply return without driving any retry logic
                if (_serverStopping) {
                    if (tc.isEntryEnabled())
                        Tr.exit(tc, "updateServerLease", "server stopping");
                    return;
                }

                boolean failAndReport = true;
                // If currentSqlEx is non-null, then we potentially have a condition that may be retried. If it is not null, then
                // the nonTransientException will have been set.
                if (currentSqlEx != null) {
                    // Set the exception that will be reported
                    nonTransientException = currentSqlEx;
                    UpdateServerLeaseRetry updateServerLeaseRetry = new UpdateServerLeaseRetry(recoveryIdentity, recoveryGroup, isServerStartup);
                    updateServerLeaseRetry.setNonTransientException(currentSqlEx);
                    // The following method will reset "nonTransientException" if it cannot recover
                    if (_sqlTransientErrorHandlingEnabled) {
                        failAndReport = updateServerLeaseRetry.retryAfterSQLException(this, currentSqlEx);

                        if (failAndReport)
                            nonTransientException = updateServerLeaseRetry.getNonTransientException();
                    }
                }

                // We've been through the while loop
                if (failAndReport) {
                    Tr.audit(tc, "WTRN0100E: " +
                                 "Cannot recover from SQLException when updating server lease for server with identity " + recoveryIdentity + " Exception: "
                                 + nonTransientException);
                    if (nonTransientException instanceof RecoveryFailedException) {
                        RecoveryFailedException rex = (RecoveryFailedException) nonTransientException;
                        if (tc.isEntryEnabled())
                            Tr.exit(tc, "updateServerLease", "RecoveryFailedException");
                        throw rex;
                    } else {
                        if (tc.isEntryEnabled())
                            Tr.exit(tc, "updateServerLease", "InternalLogException");
                        throw new InternalLogException(nonTransientException);
                    }
                } else {
                    Tr.audit(tc, "WTRN0108I: Have recovered from SQLException when updating server lease for server with identity " + recoveryIdentity);
                }
            }

            if (_updatelockingRS != null && !_updatelockingRS.isClosed())
                _updatelockingRS.close();
            if (_lockingStmt != null && !_lockingStmt.isClosed())
                _lockingStmt.close();
            if (conn != null)
                conn.close();
        }

        if (tc.isEntryEnabled())
            Tr.exit(tc, "updateServerLease");
    }

    private boolean queryLeaseTable(String recoveryIdentity, Connection conn, boolean isServerStartup) throws Exception {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "queryLeaseTable", new java.lang.Object[] { recoveryIdentity, conn, _updatelockingRS, isServerStartup, this });
        boolean newTable = true;
        Exception currentEx = null;
        _lockingStmt = conn.createStatement();
        try {
            String queryString = "SELECT LEASE_TIME, LEASE_OWNER" +
                                 " FROM " + _leaseTableName +
                                 " WHERE SERVER_IDENTITY='" + recoveryIdentity + "'" +
                                 (_isPostgreSQL ? "" : " FOR UPDATE OF LEASE_TIME");
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Attempt to select the row for UPDATE using - " + queryString);
            _updatelockingRS = _lockingStmt.executeQuery(queryString);

            newTable = false;
        } catch (Exception e) {
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Query failed with exception: " + e);
            currentEx = e;
        }

        if (currentEx != null) {
            if (!_serverStopping) {
                if (isServerStartup) {
                    // Perhaps we couldn't find the table ... so attempt to create it
                    synchronized (_CreateTableLock) // Guard against trying to create a table from multiple threads
                    {
                        try {
                            Tr.audit(tc, "WTRN0108I: Create Shared Lease Table");
                            createLeaseTable(conn);

                            conn.commit();

                            newTable = true;
                        } catch (Exception ine) {
                            if (tc.isDebugEnabled())
                                Tr.debug(tc, "Table Creation failed with exception: " + ine);
                            // Set the current exception to ine
                            throw ine;
                        }
                    } // eof synchronize block
                } // eof isServerStartup
                else {
                    if (tc.isDebugEnabled())
                        Tr.debug(tc, "Lease select update failed with exception: " + currentEx);
                    // Set the current exception to ine
                    throw currentEx;
                }
            } else { // server is stopping report but return without throwing exception
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Lease select update failed but server is stopping, exception: " + currentEx);
            }
        }

        if (tc.isEntryEnabled())
            Tr.exit(tc, "queryLeaseTable", newTable);
        return newTable;
    }

    private boolean updateLeaseTable(String recoveryIdentity, String recoveryGroup, Connection conn,
                                     boolean isServerStartup) throws Exception {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "updateLeaseTable", new java.lang.Object[] { recoveryIdentity, recoveryGroup, conn, _updatelockingRS, isServerStartup, this });
        boolean needInsert = true;
        PreparedStatement updateStmt = null;
        try {
            // We havent just created the table, see if we have a row to update
            if (_updatelockingRS.next()) {
                // We found the Server row
                long storedLease = _updatelockingRS.getLong(1);
                String storedLeaseOwner = _updatelockingRS.getString(2);
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Acquired lock row, stored lease value is: " + Utils.traceTime(storedLease) + ", stored owner is: " + storedLeaseOwner);

                // If this is startup, check whether lease has expired
                if (isServerStartup) {
                    PeerLeaseData pld = new PeerLeaseData(recoveryIdentity, storedLease, _leaseTimeout);
                    if (pld.isExpired()) {
                        if (tc.isDebugEnabled())
                            Tr.debug(tc, "Lease has expired, we should update lease and recover");
                        // We have acquired the lock. If any other server is also trying to peer recover then
                        // they can fail. To make this happen, we'll update the lease time ourselves.
                    } else {
                        // Lease has not expired, are we the owner or is some other server peer recovering
                        // If the latter, then we should barf
                        if (!storedLeaseOwner.equals(recoveryIdentity)) {
                            final String dbg = storedLeaseOwner + " is recovering our logs, we will fail our recovery";
                            if (tc.isDebugEnabled())
                                Tr.debug(tc, dbg);
                            final RecoveryFailedException rex = new RecoveryFailedException(dbg);
                            throw rex;
                        }
                    }
                }

                // Construct the UPDATE string
                String updateString = "UPDATE " + _leaseTableName +
                                      " SET LEASE_TIME = ?, RECOVERY_GROUP = ?, LEASE_OWNER = ?" +
                                      " WHERE SERVER_IDENTITY='" + recoveryIdentity + "'";
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "update lease for " + recoveryIdentity);

                updateStmt = conn.prepareStatement(updateString);
                // Set the Lease_time to the current time
                //TODO:
                long fir1 = System.currentTimeMillis();
                updateStmt.setLong(1, fir1);
                updateStmt.setString(2, recoveryGroup);
                updateStmt.setString(3, recoveryIdentity);
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Ready to UPDATE using string - " + updateString + " and time: " + Utils.traceTime(fir1));

                int ret = updateStmt.executeUpdate();

                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Have updated Server row with return: " + ret);
                needInsert = false;
            } else {
                // We didn't find the row in the table
                //TODO: Should we insert a new one?
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Could not find row");
            }
        } finally {
            if (updateStmt != null && !updateStmt.isClosed())
                updateStmt.close();
        }

        if (tc.isEntryEnabled())
            Tr.exit(tc, "updateLeaseTable", needInsert);
        return needInsert;
    }

    /**
     * Insert a new lease in the table
     *
     * @param recoveryIdentity
     * @param recoveryGroup
     * @param conn
     * @throws SQLException
     */
    private void insertNewLease(String recoveryIdentity, String recoveryGroup, Connection conn) throws SQLException {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "insertNewLease", this);

        short serviceId = (short) 1;
        String insertString = "INSERT INTO " +
                              _leaseTableName +
                              " (SERVER_IDENTITY, RECOVERY_GROUP, LEASE_OWNER, LEASE_TIME)" +
                              " VALUES (?,?,?,?)";

        PreparedStatement specStatement = null;
        long fir1 = System.currentTimeMillis();

        Tr.audit(tc, "WTRN0108I: Insert New Lease for server with recovery identity " + recoveryIdentity);
        try {
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Need to setup new row using - " + insertString + ", and time: " + Utils.traceTime(fir1));
            specStatement = conn.prepareStatement(insertString);
            specStatement.setString(1, recoveryIdentity);
            specStatement.setString(2, recoveryGroup);
            specStatement.setString(3, recoveryIdentity);
            specStatement.setLong(4, fir1);

            int ret = specStatement.executeUpdate();

            if (tc.isDebugEnabled())
                Tr.debug(tc, "Have inserted Server row with return: " + ret);
        } finally {
            if (specStatement != null && !specStatement.isClosed())
                specStatement.close();
        }
        if (tc.isEntryEnabled())
            Tr.exit(tc, "insertNewLease");
    }

    private DataSource getDataSourceFromProperties() throws Exception {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "getDataSourceFromProperties", this);
        DataSource dataSource = null;
        Properties internalLogProperties = _customLogProperties.properties();

        if (tc.isDebugEnabled())
            Tr.debug(tc, "Recovery log internal properties are " + internalLogProperties);

        String fullLogDirectory = internalLogProperties.getProperty("LOG_DIRECTORY");
        if (tc.isDebugEnabled())
            Tr.debug(tc, "fullLogDirectory = " + fullLogDirectory);
        // Parse database properties
        StringTokenizer st = new StringTokenizer(fullLogDirectory, "?");
        String cname = st.nextToken();
        if (tc.isDebugEnabled())
            Tr.debug(tc, "cname = " + cname);

        // Extract the DB related properties
        Properties dbStringProps = new Properties();
        String dbPropertiesString = st.nextToken();
        if (tc.isDebugEnabled())
            Tr.debug(tc, "dbPropertiesString = " + dbPropertiesString);

        dbStringProps.load(new StringReader(dbPropertiesString.replace(',', '\n')));
        if (tc.isDebugEnabled())
            Tr.debug(tc, "dbStringProps = " + dbStringProps);

        // Extract the DSName
        String dsName = dbStringProps.getProperty("datasource");
        if (tc.isDebugEnabled())
            Tr.debug(tc, "Extracted Data Source name = " + dsName);

        // We'll look up the DataSource definition in jndi but jndi is initialising in another
        // thread so we need to handle the situation where a first lookup does not succeed. This
        // processing is wrapped up in the SQLNonTransactionalDataSource
        SQLNonTransactionalDataSource sqlNonTranDS = new SQLNonTransactionalDataSource(dsName, _customLogProperties);

        dataSource = sqlNonTranDS.getDataSource();

        if (tc.isEntryEnabled())
            Tr.exit(tc, "getDataSourceFromProperties", dataSource);
        return dataSource;
    }

    @Override
    public Connection getConnection() throws Exception {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "getConnection", this);
        Connection conn = null;

        boolean retryOnRAExc = false;

        // If the enableLogRetries attribute has been set to "true" in the transaction stanza of the server.xml, then we will retry
        // SQL operations when general SQLExceptions are encountered
        if (ConfigurationProviderManager.getConfigurationProvider().enableLogRetries()) {
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Set the logRetriesEnabled flag to true");
            _logRetriesEnabled = true;
            SQLRetry.setLogRetriesEnabled(true);
            // If the logRetriesEnabled flag has been explicitly set, then we will retry SQL operations on all databases.
            _sqlTransientErrorHandlingEnabled = true;
        } else {
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Set the logRetriesEnabled flag to false");
            // If _logRetriesEnabled has been reset (config change) and if the database is non-standard, then we will
            // no longer retry SQLExceptions
            if (_logRetriesEnabled && _isNonStandard)
                _sqlTransientErrorHandlingEnabled = false;
            _logRetriesEnabled = false;
            SQLRetry.setLogRetriesEnabled(false);
        }

        // Have we acquired the reference to the DataSource yet?
        if (_theDS == null) {
            _theDS = getDataSourceFromProperties();
        }

        // Get connection to database via datasource
        try {
            if (_theDS != null)
                conn = _theDS.getConnection();
        } catch (SQLException sqlex) {
            // Handle the special case where the DataSource has been refreshed and the Connection Pool has shut down
            Throwable cause = sqlex.getCause();
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Caught SQLException with cause: " + cause);

            if (cause instanceof ResourceAllocationException) {// javax.resource.spi.ResourceAllocationException
                // Look up the DataSource definition again
                retryOnRAExc = true;
            } else {
                // Not a ResourceAllocationException, rethrow exception
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "getConnection", "SQLException");
                throw sqlex;
            }
        }

        // Look up the DataSource definition again in order to get a Connection
        if (retryOnRAExc) {
            _theDS = getDataSourceFromProperties();

            try {
                if (_theDS != null) {
                    conn = _theDS.getConnection();
                    Tr.audit(tc, "WTRN0108I: " +
                                 "Have recovered from ResourceAllocationException in connection to SQL Lease Log");
                }
            } catch (Throwable exc) {
                SQLException newsqlex;
                if (exc instanceof SQLException) {
                    newsqlex = (SQLException) exc;

                } else {
                    // Wrap in a SQLException
                    newsqlex = new SQLException(exc);
                }
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "getConnection", "new SQLException");
                throw newsqlex;
            }
        }

        if (conn != null && !_determineDBType) {
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Got connection: " + conn);
            DatabaseMetaData mdata = conn.getMetaData();
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Got metadata: " + mdata);
            String dbName = mdata.getDatabaseProductName();
            if (dbName.toLowerCase().contains("oracle")) {
                _isOracle = true;
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "This is an Oracle Database");
            } else if (dbName.toLowerCase().contains("postgresql")) {
                // we are PostgreSQL
                _isPostgreSQL = true;
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "This is a PostgreSQL Database");
            } else if (dbName.toLowerCase().contains("db2")) {
                // we are DB2
                _isDB2 = true;
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "This is a DB2 Database");
            } else if (dbName.toLowerCase().contains("microsoft sql")) {
                // we are MS SQL Server
                _isSQLServer = true;
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "This is a Microsoft SQL Server Database");
            } else if (dbName.toLowerCase().contains("derby")) {
                // we are Derby
                _isDerby = true;
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "This is a Derby Database");
            } else {
                _isNonStandard = true;
                // We're not working with a member of the standard set of databases. The "default" behaviour is not to retry for such non-standard, untested databases,
                // even if the exception is a SQLTransientException. But if the logRetriesEnabled flag has been explicitly set, then we will retry SQL
                // operations on all databases.
                if (!_logRetriesEnabled)
                    _sqlTransientErrorHandlingEnabled = false;
            }

            String dbVersion = mdata.getDatabaseProductVersion();
            if (tc.isDebugEnabled())
                Tr.debug(tc, "You are now connected to " + dbName + ", version " + dbVersion);
            _determineDBType = true;
        }

        if (tc.isEntryEnabled())
            Tr.exit(tc, "getConnection", conn);
        return conn;
    }

    //------------------------------------------------------------------------------
    // Method: SQLMultiScopeRecoveryLog.createDBTable
    //------------------------------------------------------------------------------
    /**
     * Creates the database table that is being used for the recovery
     * log.
     *
     * @exception SQLException thrown if a SQLException is
     *                encountered when accessing the
     *                Database.
     */
    private void createLeaseTable(Connection conn) throws SQLException {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "createLeaseTable", new java.lang.Object[] { conn, this });

        Statement createTableStmt = null;

        try {
            createTableStmt = conn.createStatement();

            if (_isOracle) {
                String oracleTableString = oracleTablePreString + _leaseTableName + oracleTablePostString;
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Create Oracle Table using: " + oracleTableString);
                createTableStmt.executeUpdate(oracleTableString);
            } else if (_isPostgreSQL) {
                String postgreSQLTableString = postgreSQLTablePreString + _leaseTableName + postgreSQLTablePostString;
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Create PostgreSQL Table using: " + postgreSQLTableString);
                conn.rollback();
                createTableStmt.execute(postgreSQLTableString);
            } else {
                String genericTableString = genericTablePreString + _leaseTableName + genericTablePostString;
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Create Generic Table using: " + genericTableString);
                createTableStmt.executeUpdate(genericTableString);
            }

        } finally {
            if (createTableStmt != null && !createTableStmt.isClosed()) {
                createTableStmt.close();
            }
        }

        if (tc.isEntryEnabled())
            Tr.exit(tc, "createLeaseTable");
    }

    /*
     * This method supports the deletion of a server's lease. We have to be a little careful as we may be deleting the lease for a peer
     * and that peer may have restarted. In this case we should test that the lease is
     *
     * that we have recoveredRead lease check if it is still expired. If so, then update lease and proceed to peer recover
     * if not still expired (someone else has grabbed it) then bypass peer recover.
     *
     * @see com.ibm.ws.recoverylog.spi.SharedServerLeaseLog#deleteServerLease(java.lang.String)
     */
//TODO:HEY ISSUE HERE IS THAT in the case where we are doing peer recovery we will have updated the lease ourselves at the start of peer
//TODO:recovery. How do we know if original server has re-started and needs the lease to NOT be deleted? Or does it matter? ie if the lease
//TODO:is deleted by a peer, could the original server not simply (re)insert its own row?
    @Override
    public synchronized void deleteServerLease(String recoveryIdentity) throws Exception {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "deleteServerLease", new java.lang.Object[] { recoveryIdentity, this });

        Connection conn = null;

        boolean deleteSuccess = false;
        // For exception handling
        Throwable nonTransientException = null;
        SQLException currentSqlEx = null;

        // if the server is stopping, we should simply return
        if (_serverStopping) {
            if (tc.isEntryEnabled())
                Tr.exit(tc, "deleteServerLease", "server stopping");
            return;
        }

        try {
            // Get a connection to the DB
            conn = getConnection();

            // If we were unable to get a connection, throw an exception
            if (conn == null) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "deleteServerLease", "Null connection InternalLogException");
                throw new InternalLogException("Failed to get JDBC Connection", null);
            }

            if (tc.isDebugEnabled())
                Tr.debug(tc, "Set autocommit FALSE on the connection");
            conn.setAutoCommit(false);

            // We can go ahead and delete from the Database

            int ret = deleteLeaseFromTable(recoveryIdentity, conn);

            if (tc.isDebugEnabled())
                Tr.debug(tc, "Have deleted row with return: " + ret + ", commit the change");

            conn.commit();
            deleteSuccess = true;
        }
        // Catch and report an SQLException. In the finally block we'll determine whether the condition is transient or not.
        catch (SQLException sqlex) {
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Lease delete failed with exception: " + sqlex);
            Tr.audit(tc, "WTRN0107W: " +
                         "Caught SQLException when deleting lease for server with identity: " + recoveryIdentity + ", exc: " + sqlex);
            // Set the exception that will be reported
            currentSqlEx = sqlex;
        } catch (Throwable exc) {
            Tr.audit(tc, "WTRN0107W: " +
                         "Caught non-SQLException Throwable when deleting lease for server with identity: " + recoveryIdentity + ", exc: " + exc);
            nonTransientException = exc;
        } finally {

            if (!deleteSuccess) {
                // Tidy up current connection before dropping into retry code.
                // If it fails, trace the failure but allow processing to continue
                try {
                    if (_deleteStmt != null && !_deleteStmt.isClosed())
                        _deleteStmt.close();
                    if (conn != null)
                        conn.close();
                } catch (Throwable exc) {
                    // Trace the exception
                    if (tc.isDebugEnabled())
                        Tr.debug(tc, "Tidy up Failed, after lease delete failure, got exception: " + exc);
                }

                // if the server is stopping, we should simply return without driving any retry logic
                if (_serverStopping) {
                    if (tc.isEntryEnabled())
                        Tr.exit(tc, "deleteServerLease", "server stopping");
                    return;
                }

                boolean failAndReport = true;
                // If currentSqlEx is non-null, then we potentially have a condition that may be retried. If it is not null, then
                // the nonTransientException will have been set.
                if (currentSqlEx != null) {
                    // Set the exception that will be reported
                    nonTransientException = currentSqlEx;
                    DeleteServerLeaseRetry deleteServerLeaseRetry = new DeleteServerLeaseRetry(recoveryIdentity);
                    deleteServerLeaseRetry.setNonTransientException(currentSqlEx);
                    // The following method will reset "nonTransientException" if it cannot recover
                    if (_sqlTransientErrorHandlingEnabled) {
                        failAndReport = deleteServerLeaseRetry.retryAfterSQLException(this, currentSqlEx);

                        if (failAndReport)
                            nonTransientException = deleteServerLeaseRetry.getNonTransientException();
                    }
                }

                // We've been through the while loop
                if (failAndReport) {
                    Tr.audit(tc, "WTRN0100E: " +
                                 "Cannot recover from SQLException when deleting server lease for server with identity " + recoveryIdentity + " Exception: "
                                 + nonTransientException);

                    if (tc.isEntryEnabled())
                        Tr.exit(tc, "deleteServerLease", "InternalLogException");
                    throw new InternalLogException(nonTransientException);

                } else {
                    Tr.audit(tc, "WTRN0108I: Have recovered from SQLException when deleting server lease for server with identity " + recoveryIdentity);
                }
            }

            if (_deleteStmt != null && !_deleteStmt.isClosed())
                _deleteStmt.close();
            if (conn != null)
                conn.close();

        }

        if (tc.isEntryEnabled())
            Tr.exit(tc, "deleteServerLease");
    }

    private int deleteLeaseFromTable(String recoveryIdentity, Connection conn) throws Exception {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "deleteLeaseFromTable", new java.lang.Object[] { recoveryIdentity, conn, this });

        _deleteStmt = conn.createStatement();

        String deleteString = "DELETE FROM " + _leaseTableName +
                              " WHERE SERVER_IDENTITY='" + recoveryIdentity + "'";
        if (tc.isDebugEnabled())
            Tr.debug(tc, "delete server lease for " + recoveryIdentity + "using string " + deleteString);

        int ret = _deleteStmt.executeUpdate(deleteString);

        if (tc.isEntryEnabled())
            Tr.exit(tc, "deleteLeaseFromTable", ret);
        return ret;
    }

    /**
     * Read lease check if it is still expired. If so, then update lease and proceed to peer recover
     * if not still expired (someone else has grabbed it) then bypass peer recover.
     *
     * @param recoveryIdentityToRecover
     * @param myRecoveryIdentity
     * @throws Exception
     */
    @Override
    public synchronized boolean claimPeerLeaseForRecovery(String recoveryIdentityToRecover, String myRecoveryIdentity, LeaseInfo leaseInfo) throws Exception {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "claimPeerLeaseForRecovery", new java.lang.Object[] { recoveryIdentityToRecover, myRecoveryIdentity, leaseInfo, this });

        boolean peerClaimed = false;
        boolean peerClaimSuccess = false;
        Connection conn = null;
        // For exception handling
        Throwable nonTransientException = null;
        SQLException currentSqlEx = null;

        // if the server is stopping, we should simply return
        if (_serverStopping) {
            if (tc.isEntryEnabled())
                Tr.exit(tc, "claimPeerLeaseForRecovery", "server stopping");
            return false;
        }

        if (tc.isDebugEnabled())
            Tr.debug(tc, "Recovering server with recoveryIdentity - ", recoveryIdentityToRecover);

        try {
            // Get a connection to the DB
            conn = getConnection();

            // If we were unable to get a connection, throw an exception
            if (conn == null) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "claimPeerLeaseForRecovery", "Null connection InternalLogException");
                throw new InternalLogException("Failed to get JDBC Connection", null);
            }

            if (tc.isDebugEnabled())
                Tr.debug(tc, "Set autocommit FALSE on the connection");
            conn.setAutoCommit(false);

            peerClaimed = claimPeerLeaseFromTable(recoveryIdentityToRecover, myRecoveryIdentity, leaseInfo, conn);

            if (tc.isDebugEnabled())
                Tr.debug(tc, "COMMIT the change");
            conn.commit();
            peerClaimSuccess = true;
        }
        // Catch and report an SQLException. In the finally block we'll determine whether the condition is transient or not.
        catch (SQLException sqlex) {
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Lease claim failed with exception: " + sqlex);
            Tr.audit(tc, "WTRN0107W: " +
                         "Caught SQLException for server with recovery identity " + myRecoveryIdentity +
                         " when claiming peer lease for server with recovery identity " + recoveryIdentityToRecover + ", exc: " + sqlex);
            // Set the exception that will be reported
            currentSqlEx = sqlex;
        } catch (Throwable exc) {
            Tr.audit(tc, "WTRN0107W: " +
                         "Caught non-SQLException Throwable for server with recovery identity " + myRecoveryIdentity +
                         " when claiming peer lease for server with recovery identity " + recoveryIdentityToRecover + ", exc: " + exc);
            nonTransientException = exc;
        } finally {

            if (!peerClaimSuccess) {
                // Tidy up current connection before dropping into retry code.
                // If it fails, trace the failure but allow processing to continue
                try {
                    if (_claimPeerLockingRS != null && !_claimPeerLockingRS.isClosed())
                        _claimPeerLockingRS.close();
                    if (_claimPeerlockingStmt != null && !_claimPeerlockingStmt.isClosed())
                        _claimPeerlockingStmt.close();
                    if (_claimPeerUpdateStmt != null && !_claimPeerUpdateStmt.isClosed())
                        _claimPeerUpdateStmt.close();
                    if (conn != null)
                        conn.close();
                } catch (Throwable exc) {
                    // Trace the exception
                    if (tc.isDebugEnabled())
                        Tr.debug(tc, "Tidy up Failed, after lease claim failure, got exception: " + exc);
                }

                // if the server is stopping, we should simply return without driving any retry logic
                if (_serverStopping) {
                    if (tc.isEntryEnabled())
                        Tr.exit(tc, "claimPeerLeaseForRecovery", "server stopping");
                    return false;
                }

                boolean failAndReport = true;
                // If currentSqlEx is non-null, then we potentially have a condition that may be retried. If it is not null, then
                // the nonTransientException will have been set.
                if (currentSqlEx != null) {
                    // Set the exception that will be reported
                    nonTransientException = currentSqlEx;
                    ClaimPeerLeaseRetry claimPeerLeaseRetry = new ClaimPeerLeaseRetry(recoveryIdentityToRecover, myRecoveryIdentity, leaseInfo);
                    claimPeerLeaseRetry.setNonTransientException(currentSqlEx);
                    // The following method will reset "nonTransientException" if it cannot recover
                    if (_sqlTransientErrorHandlingEnabled) {
                        failAndReport = claimPeerLeaseRetry.retryAfterSQLException(this, currentSqlEx);

                        if (failAndReport)
                            nonTransientException = claimPeerLeaseRetry.getNonTransientException();
                    }
                    peerClaimed = claimPeerLeaseRetry.getPeerClaimed();
                }

                // We've been through the while loop
                if (failAndReport) {
                    Tr.audit(tc, "WTRN0100E: " +
                                 "Cannot recover from SQLException for server with recovery identity " + myRecoveryIdentity +
                                 "when claiming peer lease for server with recovery identity " + recoveryIdentityToRecover + ", Exception: " + nonTransientException);

                    if (tc.isEntryEnabled())
                        Tr.exit(tc, "getLeasesForPeers", "InternalLogException");
                    throw new InternalLogException(nonTransientException);

                } else {
                    Tr.audit(tc, "WTRN0108I: Have recovered from SQLException for server with recovery identity " + myRecoveryIdentity +
                                 "when claiming peer lease for server with recovery identity " + recoveryIdentityToRecover + ", was peer claimed: " + peerClaimed);
                }
            }

            if (_claimPeerLockingRS != null && !_claimPeerLockingRS.isClosed())
                _claimPeerLockingRS.close();
            if (_claimPeerlockingStmt != null && !_claimPeerlockingStmt.isClosed())
                _claimPeerlockingStmt.close();
            if (_claimPeerUpdateStmt != null && !_claimPeerUpdateStmt.isClosed())
                _claimPeerUpdateStmt.close();
            if (conn != null)
                conn.close();
        }

        if (tc.isEntryEnabled())
            Tr.exit(tc, "claimPeerLeaseForRecovery");
        return peerClaimed;
    }

    private boolean claimPeerLeaseFromTable(String recoveryIdentityToRecover, String myRecoveryIdentity, LeaseInfo leaseInfo, Connection conn) throws Exception {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "claimPeerLeaseFromTable", new java.lang.Object[] { recoveryIdentityToRecover, myRecoveryIdentity, leaseInfo, conn, this });

        boolean peerClaimed = false;

        // We can go ahead and access the Database
        _claimPeerlockingStmt = conn.createStatement();

        try {
            String queryString = "SELECT LEASE_TIME" +
                                 " FROM " + _leaseTableName +
                                 " WHERE SERVER_IDENTITY='" + recoveryIdentityToRecover + "'" +
                                 (_isPostgreSQL ? "" : " FOR UPDATE OF LEASE_TIME");
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Attempt to select the row for UPDATE using - " + queryString);
            _claimPeerLockingRS = _claimPeerlockingStmt.executeQuery(queryString);
        } catch (Exception e) {
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Query failed with exception: " + e);
            throw new InternalLogException("Failed to query the lease time, exc: " + e, null);
        } // eof Exception e block

        // see if we acquired the row
        if (_claimPeerLockingRS.next()) {
            // We found the server row
            long storedLease = _claimPeerLockingRS.getLong(1);
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Acquired server row, stored lease value is: " + Utils.traceTime(storedLease));

            // Has the lease expired?
            PeerLeaseData pld = new PeerLeaseData(recoveryIdentityToRecover, storedLease, _leaseTimeout);
            if (pld.isExpired()) {
                // Lease has expired, this means that this server is attempting peer recovery and
                // it has acquired the lock. If any other server is also trying to peer recover then
                // they can fail. To make this happen, we'll update the lease time ourselves.

                // Construct the UPDATE string
                String updateString = "UPDATE " + _leaseTableName +
                                      " SET LEASE_TIME = ?, LEASE_OWNER = ?" +
                                      " WHERE SERVER_IDENTITY='" + recoveryIdentityToRecover + "'";
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "update lease for " + recoveryIdentityToRecover);

                _claimPeerUpdateStmt = conn.prepareStatement(updateString);

                // Set the Lease_time to the current time
                //TODO:
                long fir1 = System.currentTimeMillis();
                _claimPeerUpdateStmt.setLong(1, fir1);
                _claimPeerUpdateStmt.setString(2, myRecoveryIdentity);
                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Ready to UPDATE using string - " + updateString + " and time: " + Utils.traceTime(fir1));

                int ret = _claimPeerUpdateStmt.executeUpdate();

                if (tc.isDebugEnabled())
                    Tr.debug(tc, "Have updated server row with return: " + ret);
                peerClaimed = true;
            }
        } else {
            // We didn't find the row in the table
            if (tc.isDebugEnabled())
                Tr.debug(tc, "Could not find row");
            throw new InternalLogException("Could not find lease", null);
        }

        if (tc.isEntryEnabled())
            Tr.exit(tc, "claimPeerLeaseForRecovery");
        return peerClaimed;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.ws.recoverylog.spi.SharedServerLeaseLog#lockPeerLease(java.lang.String)
     */
    @Override
    public boolean lockPeerLease(String recoveryIdentity) {
        // Noop in RDBMS implementation
        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.ws.recoverylog.spi.SharedServerLeaseLog#releasePeerLease(java.lang.String)
     */
    @Override
    public boolean releasePeerLease(String recoveryIdentity) throws Exception {
        // Noop in RDBMS implementation
        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.ws.recoverylog.spi.SharedServerLeaseLog#lockLocalLease(java.lang.String)
     */
    @Override
    public boolean lockLocalLease(String recoveryIdentity) {
        // Noop in RDBMS implementation
        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.ws.recoverylog.spi.SharedServerLeaseLog#releaseLocalLease(java.lang.String)
     */
    @Override
    public boolean releaseLocalLease(String recoveryIdentity) throws Exception {
        // Noop in RDBMS implementation
        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.ws.recoverylog.spi.SharedServerLeaseLog#setPeerRecoveryLeaseTimeout(int)
     */
    @Override
    public void setPeerRecoveryLeaseTimeout(int leaseTimeout) {
        if (tc.isDebugEnabled())
            Tr.debug(tc, "setPeerRecoveryLeaseTimeout", leaseTimeout);

        // Store the Lease Timeout
        _leaseTimeout = leaseTimeout;
    }

    /**
     * Signals to the Lease Log that the server is stopping.
     */
    @Override
    public void serverStopping() {
        if (tc.isEntryEnabled())
            Tr.entry(tc, "serverStopping ", new Object[] { this });

        _serverStopping = true;

        if (tc.isEntryEnabled())
            Tr.exit(tc, "serverStopping", this);
    }

    /**
     * This concrete class extends SQLRetry providing the lease update code to be retried.
     *
     */
    class UpdateServerLeaseRetry extends SQLRetry {

        String _recoveryIdentity;
        String _recoveryGroup;
        boolean _isServerStartup;

        public UpdateServerLeaseRetry(String recoveryIdentity, String recoveryGroup, boolean isServerStartup) {
            if (tc.isEntryEnabled())
                Tr.entry(tc, "UpdateServerLeaseRetry", new Object[] { recoveryIdentity, recoveryGroup, isServerStartup, this });

            // Cache the supplied information
            _recoveryIdentity = recoveryIdentity;
            _recoveryGroup = recoveryGroup;
            _isServerStartup = isServerStartup;

            if (tc.isEntryEnabled())
                Tr.exit(tc, "UpdateServerLeaseRetry", this);
        }

        @Override
        public void retryCode(Connection conn) throws SQLException, Exception {
            if (tc.isEntryEnabled())
                Tr.entry(tc, "UpdateServerLeaseRetry.retryCode", new Object[] { conn });

            // If we were unable to get a connection, throw an exception, but not if we're stopping
            if (_serverStopping) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "UpdateServerLeaseRetry.retryCode", "server stopping");
                return;
            }

            if (conn == null) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "UpdateServerLeaseRetry.retryCode", "Null connection InternalLogException");
                throw new InternalLogException("Failed to get JDBC Connection", null);
            }

            if (tc.isDebugEnabled())
                Tr.debug(tc, "Set autocommit FALSE on the connection");
            conn.setAutoCommit(false);

            // We can go ahead and query the Database
            boolean newTable = queryLeaseTable(_recoveryIdentity, conn, _isServerStartup);
            // if the server is stopping, we should simply return
            if (_serverStopping) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "UpdateServerLeaseRetry.retryCode", this);
                return;
            }

            boolean needInsert = true;
            if (!newTable)
                needInsert = updateLeaseTable(_recoveryIdentity, _recoveryGroup, conn, _isServerStartup);

            // Either a new table or we couldn't find the row for our server. Insert it.
            if (needInsert) {
                if (!_serverStopping) {
                    // Insert a new row into the lease table
                    insertNewLease(_recoveryIdentity, _recoveryGroup, conn);
                } else { // server is stopping exit without insert
                    if (tc.isEntryEnabled())
                        Tr.exit(tc, "UpdateServerLeaseRetry.retryCode", "skip insert server is stopping");
                    return;
                }
            }
            if (tc.isEntryEnabled())
                Tr.exit(tc, "UpdateServerLeaseRetry.retryCode");
        }

        @Override
        public String getOperationDescription() {
            return "updating server lease";
        }

    }

    /**
     * This concrete class extends SQLRetry providing the lease deletion code to be retried.
     *
     */
    class DeleteServerLeaseRetry extends SQLRetry {

        String _recoveryIdentity;

        public DeleteServerLeaseRetry(String recoveryIdentity) {
            if (tc.isEntryEnabled())
                Tr.entry(tc, "DeleteServerLeaseRetry", new Object[] { recoveryIdentity, this });

            // Cache the supplied information
            _recoveryIdentity = recoveryIdentity;

            if (tc.isEntryEnabled())
                Tr.exit(tc, "DeleteServerLeaseRetry", this);
        }

        @Override
        public void retryCode(Connection conn) throws SQLException, Exception {
            if (tc.isEntryEnabled())
                Tr.entry(tc, "DeleteServerLeaseRetry.retryCode", new Object[] { conn });

            // If we were unable to get a connection, throw an exception, but not if we're stopping
            if (_serverStopping) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "DeleteServerLeaseRetry.retryCode", "server stopping");
                return;
            }
            if (conn == null) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "DeleteServerLeaseRetry.retryCode", "Null connection InternalLogException");
                throw new InternalLogException("Failed to get JDBC Connection", null);
            }

            if (tc.isDebugEnabled())
                Tr.debug(tc, "Set autocommit FALSE on the connection");
            conn.setAutoCommit(false);

            // We can go ahead and delete from the Database
            int ret = deleteLeaseFromTable(_recoveryIdentity, conn);

            if (tc.isDebugEnabled())
                Tr.debug(tc, "Have deleted row with return: " + ret + ", commit the change");
            if (tc.isEntryEnabled())
                Tr.exit(tc, "DeleteServerLeaseRetry.retryCode");
        }

        @Override
        public String getOperationDescription() {
            return "delete server lease";
        }

    }

    /**
     * This concrete class extends SQLRetry providing the lease retrieval code to be retried.
     *
     */
    class GetPeerLeaseRetry extends SQLRetry {

        String _recoveryGroup;
        PeerLeaseTable _peerLeaseTable;

        public GetPeerLeaseRetry(final PeerLeaseTable peerLeaseTable, String recoveryGroup) {
            if (tc.isEntryEnabled())
                Tr.entry(tc, "GetPeerLeaseRetry", new Object[] { recoveryGroup, this });

            // Cache the supplied information
            _recoveryGroup = recoveryGroup;
            _peerLeaseTable = peerLeaseTable;

            if (tc.isEntryEnabled())
                Tr.exit(tc, "GetPeerLeaseRetry", this);
        }

        @Override
        public void retryCode(Connection conn) throws SQLException, Exception {
            if (tc.isEntryEnabled())
                Tr.entry(tc, "GetPeerLeaseRetry.retryCode", new Object[] { conn });

            // If we were unable to get a connection, throw an exception, but not if we're stopping
            if (_serverStopping) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "GetPeerLeaseRetry.retryCode", "server stopping");
                return;
            }
            if (conn == null) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "GetPeerLeaseRetry.retryCode", "Null connection InternalLogException");
                throw new InternalLogException("Failed to get JDBC Connection", null);
            }

            if (tc.isDebugEnabled())
                Tr.debug(tc, "Set autocommit FALSE on the connection");
            conn.setAutoCommit(false);

            getPeerLeasesFromTable(_peerLeaseTable, _recoveryGroup, conn);

            if (tc.isEntryEnabled())
                Tr.exit(tc, "GetPeerLeaseRetry.retryCode");
        }

        @Override
        public String getOperationDescription() {
            return "get peer lease";
        }

    }

    /**
     * This concrete class extends SQLRetry providing the lease retrieval code to be retried.
     *
     */
    class ClaimPeerLeaseRetry extends SQLRetry {

        String _recoveryIdentityToRecover;
        String _myRecoveryIdentity;
        LeaseInfo _leaseInfo;
        boolean _peerClaimed = false;

        public ClaimPeerLeaseRetry(String recoveryIdentityToRecover, String myRecoveryIdentity, LeaseInfo leaseInfo) {
            if (tc.isEntryEnabled())
                Tr.entry(tc, "ClaimPeerLeaseRetry", new Object[] { recoveryIdentityToRecover, myRecoveryIdentity, leaseInfo, this });

            // Cache the supplied information
            _recoveryIdentityToRecover = recoveryIdentityToRecover;
            _myRecoveryIdentity = myRecoveryIdentity;
            _leaseInfo = leaseInfo;

            if (tc.isEntryEnabled())
                Tr.exit(tc, "ClaimPeerLeaseRetry", this);
        }

        @Override
        public void retryCode(Connection conn) throws SQLException, Exception {
            if (tc.isEntryEnabled())
                Tr.entry(tc, "ClaimPeerLeaseRetry.retryCode", new Object[] { conn });

            // If we were unable to get a connection, throw an exception, but not if we're stopping
            if (_serverStopping) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "ClaimPeerLeaseRetry.retryCode", "server stopping");
                return;
            }
            if (conn == null) {
                if (tc.isEntryEnabled())
                    Tr.exit(tc, "ClaimPeerLeaseRetry.retryCode", "Null connection InternalLogException");
                throw new InternalLogException("Failed to get JDBC Connection", null);
            }

            if (tc.isDebugEnabled())
                Tr.debug(tc, "Set autocommit FALSE on the connection");
            conn.setAutoCommit(false);

            _peerClaimed = claimPeerLeaseFromTable(_recoveryIdentityToRecover, _myRecoveryIdentity, _leaseInfo, conn);

            if (tc.isEntryEnabled())
                Tr.exit(tc, "ClaimPeerLeaseRetry.retryCode");
        }

        boolean getPeerClaimed() {
            return _peerClaimed;
        }

        @Override
        public String getOperationDescription() {
            return "claim peer server lease";
        }

    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.ws.recoverylog.custom.jdbc.impl.SQLRetriableLog#prepareConnectionForBatch(java.sql.Connection)
     */
    @Override
    public int prepareConnectionForBatch(Connection conn) throws SQLException {
        // A no-op for this class
        return 0;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.ws.recoverylog.custom.jdbc.impl.SQLRetriableLog#closeConnectionAfterBatch(java.sql.Connection, int)
     */
    @Override
    public void closeConnectionAfterBatch(Connection conn, int initialIsolation) throws SQLException {
        // A no-op for this class
    }

}
