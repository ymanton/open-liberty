-include= ~${workspace}/cnf/resources/bnd/feature.props
symbolicName=io.openliberty.appserver.connectors-2.1
visibility=protected
singleton=true
IBM-API-Package: jakarta.resource; type="spec", \
 jakarta.resource.cci; type="spec", \
 jakarta.resource.spi; type="spec", \
 jakarta.resource.spi.endpoint; type="spec", \
 jakarta.resource.spi.security; type="spec", \
 jakarta.resource.spi.work; type="spec"
-features=com.ibm.websphere.appserver.eeCompatible-10.0, \
  io.openliberty.jakarta.connectors-2.1
-bundles=io.openliberty.jakarta.connectors.2.0; location:="dev/api/spec/,lib/"; mavenCoordinates="jakarta.resource:jakarta.resource-api:2.0.0"
kind=noship
edition=full
WLP-Activation-Type: parallel