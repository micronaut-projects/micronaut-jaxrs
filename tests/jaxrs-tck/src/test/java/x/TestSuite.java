package x;

import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectPackages("ee.jakarta.tck.ws.rs")
@IncludeClassNamePatterns("^.*IT$")
public class TestSuite {
}
