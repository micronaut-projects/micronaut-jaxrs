package x;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.ExcludePackages;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

// Split into multiple suite for faster parallel execution
public class TestSuite {

    public static final String API_TEST_PACKAGE = "ee.jakarta.tck.ws.rs.api";
    public static final String EE_TEST_PACKAGE = "ee.jakarta.tck.ws.rs.ee";
    public static final String SLOW_ASYNC_TEST = "ee.jakarta.tck.ws.rs.ee.rs.client.asyncinvoker.JAXRSClientIT";
    public static final String SLOW_SYNC_TEST = "ee.jakarta.tck.ws.rs.ee.rs.client.syncinvoker.JAXRSClientIT";

    @Suite
    @SelectPackages("ee.jakarta.tck.ws.rs")
    @ExcludePackages({EE_TEST_PACKAGE, API_TEST_PACKAGE})
    @IncludeClassNamePatterns(".*")
    public static class Tests {
    }

    @Suite
    @SelectPackages(EE_TEST_PACKAGE)
    @ExcludeClassNamePatterns({SLOW_ASYNC_TEST, SLOW_SYNC_TEST})
    @IncludeClassNamePatterns(".*")
    public static class EE_Tests {
    }

    @Suite
    @SelectPackages(API_TEST_PACKAGE)
    @IncludeClassNamePatterns(".*")
    public static class API_Tests {
    }

    @Suite
    @SelectClasses(names = SLOW_ASYNC_TEST)
    public static class SlowAsyncTests {
    }

    @Suite
    @SelectClasses(names = SLOW_SYNC_TEST)
    public static class SlowSyncTests {
    }
}
