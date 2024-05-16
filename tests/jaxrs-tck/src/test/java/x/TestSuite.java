package x;

import ee.jakarta.tck.ws.rs.ee.rs.get.JAXRSClientIT;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
//@SelectPackages("ee.jakarta.tck.ws.rs")
@SelectClasses(JAXRSClientIT.class) // todo
public class TestSuite {
}
