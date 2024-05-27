package io.micronaut.validation.tck;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;
import java.util.Arrays;

public class FilterExtension implements ExecutionCondition {
    private static final ConditionEvaluationResult SUBRESOURCES = ConditionEvaluationResult.disabled("Subresources not supported");
    private static final ConditionEvaluationResult CLIENT = ConditionEvaluationResult.disabled("JAX-RS client not supported");
    private static final ConditionEvaluationResult INVESTIGATE = ConditionEvaluationResult.disabled("investigate");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Class<?> testClass = context.getTestClass().orElse(null);
        String testMethodName = context.getTestMethod().map(Method::getName).orElse("");
        if (testClass == null) {
            return ConditionEvaluationResult.enabled("No test class or method");
        }
        if (testClass == ee.jakarta.tck.ws.rs.ee.rs.get.JAXRSClientIT.class) {
            switch (testMethodName) {
                case "headSubTest", "headTest1", "headTest2" -> {
                    return ConditionEvaluationResult.disabled("core drops HEAD bodies"); // TODO
                }
                case "dynamicGetTest", "recursiveResourceLocatorTest" -> {
                    return SUBRESOURCES;
                }
            }
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.headerparam.sub.JAXRSSubClientIT.class) {
            return SUBRESOURCES;
        } else if (ee.jakarta.tck.ws.rs.common.client.JaxrsCommonClient.class.isAssignableFrom(testClass) ||
            testClass == ee.jakarta.tck.ws.rs.api.client.client.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.core.configurable.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.client.clientresponsecontext.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.client.clientbuilder.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.client.instance.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.sebootstrap.SeBootstrapIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.bindingpriority.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.core.configuration.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.jaxrs21.ee.client.rxinvoker.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.jaxrs21.api.client.invocationbuilder.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.client.webtarget.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.client.invocationcallback.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.jaxrs21.ee.sse.ssebroadcaster.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.client.clientrequestcontext.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.client.webtarget.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.client.invocation.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.jaxrs21.ee.client.executor.rx.JAXRSClientIT.class ||
            (testClass == ee.jakarta.tck.ws.rs.api.rs.core.link.JAXRSClientIT.class && "fromResourceMethodLinkUsedInInvocationTest".equals(testMethodName))) {
            return CLIENT;
        } else if (testClass == ee.jakarta.tck.ws.rs.api.rs.core.linkbuilder.JAXRSClientIT.class) {
            if (Arrays.asList(
                "buildRelativizedThrowsIAEWhenNotSuppliedValuesTest",
                "buildObjectsTest",
                "buildNoArgsThrowsUriBuilderExceptionTest",
                "buildRelativizedThrowsUriBuilderExceptionTest",
                "buildRelativizedThrowsIAEWhenSuppliedJustOneValueOutOfThreeTest",
                "buildRelativizedThrowsIAEWhenSuppliedValueIsNullTest",
                "buildThrowsIAEWhenSuppliedJustOneValueOutOfThreeTest",
                "buildObjectsThrowsUriBuilderExceptionTest"
            ).contains(testMethodName)) {
                return INVESTIGATE;
            }
        } else if (testClass == ee.jakarta.tck.ws.rs.spec.resource.annotationprecedence.subclass.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.matrixparam.locator.JAXRSLocatorClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.resource.valueofandfromstring.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.matrixparam.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.matrixparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.ext.paramconverter.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("MatrixParam"); // TODO
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.beanparam.cookie.plain.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.beanparam.path.plain.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.beanparam.header.plain.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.beanparam.query.plain.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.beanparam.plain.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.beanparam.matrix.plain.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.beanparam.form.plain.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("BeanParam"); // TODO
        } else if (testClass == ee.jakarta.tck.ws.rs.jaxrs31.ee.multipart.MultipartSupportIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.ext.interceptor.containerreader.interceptorcontext.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("API.Status.STABLE compilation issue"); // TODO
        } else if (testClass == ee.jakarta.tck.ws.rs.signaturetest.jaxrs.JAXRSSigTestIT.class) {
            return ConditionEvaluationResult.disabled("TDK missing"); // TODO
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.container.requestcontext.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("HttpServletRequest");
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.ext.interceptor.containerwriter.writerinterceptorcontext.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.ext.interceptor.containerreader.readerinterceptorcontext.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.ext.interceptor.containerwriter.interceptorcontext.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("enum as beans"); // TODO
        } else if (testClass == ee.jakarta.tck.ws.rs.api.rs.core.variant.JAXRSClientIT.class && Arrays.asList("encodingsTest", "languagesTest", "mediaTypesTest").contains(testMethodName)) {
            return ConditionEvaluationResult.disabled("createVariantListBuilder"); // TODO
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.headerparam.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.client.entity.JAXRSClientIT.class && testMethodName.equals("entityStringThrowsExceptionWhenNullTest") || testClass == ee.jakarta.tck.ws.rs.api.rs.core.entitytag.JAXRSClientIT.class && testMethodName.equals("valueOfTest") || testClass == ee.jakarta.tck.ws.rs.api.rs.core.cookie.JAXRSClientIT.class && testMethodName.equals("parseTest3") || testClass == ee.jakarta.tck.ws.rs.api.rs.ext.runtimedelegate.create.JAXRSClientIT.class && Arrays.asList(
            "createEndpointThrowsIllegalArgumentExceptionTest",
            "createHeaderDelegateThrowsIllegalArgumentExceptionTest",
            "checkCreatedHeaderDelegateNullPointerTest",
            "checkCreatedVariantListBuilderTest"
        ).contains(testMethodName) || testClass == ee.jakarta.tck.ws.rs.ee.rs.cookieparam.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.resourceconstructor.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.sort.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.core.uriinfo.JAXRSClientIT.class ||
            (testClass == ee.jakarta.tck.ws.rs.api.rs.core.mediatype.JAXRSClientIT.class && testMethodName.equals("valueOfTest1")) ||
            testClass == ee.jakarta.tck.ws.rs.servlet3.rs.core.streamingoutput.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.resource.responsemediatype.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.core.variantlistbuilder.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.queryparam.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.headerparam.locator.JAXRSLocatorClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.contextprovider.JsonbContextProviderIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.overridestandard.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.core.request.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.client.exceptions.ClientExceptionsIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.core.securitycontext.basic.JAXRSBasicClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.pathparam.locator.JAXRSLocatorClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.cookieparam.locator.JAXRSLocatorClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.pathparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.core.cachecontrol.JAXRSClientIT.class && testMethodName.equals("valueOfTest1") ||
            testClass == ee.jakarta.tck.ws.rs.spec.template.JAXRSClientIT.class && (testMethodName.equals("Test1") || testMethodName.equals("Test2")) ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.jaxbcontext.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.exceptionmapper.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.jaxrs21.ee.priority.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.notallowedexception.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.visibility.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.jaxrs21.ee.patch.server.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.core.responseclient.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.head.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.servlet3.rs.applicationpath.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.formparam.JAXRSClientIT.class ||
            (testClass == ee.jakarta.tck.ws.rs.api.rs.core.newcookie.JAXRSClientIT.class && testMethodName.equals("parseTest3")) ||
            testClass == ee.jakarta.tck.ws.rs.jaxrs40.ee.rs.core.uriinfo.UriInfo40ClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.resource.webappexception.nomapper.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.produceconsume.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.resource.java2entity.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.uribuilder.UriBuilderIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.serviceunavailableexception.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.resource.webappexception.defaultmapper.DefaultExceptionMapperIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.standard.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.queryparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.cookieparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.core.multivaluedmap.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.standardwithxmlbinding.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.formparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.reader.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.resource.locator.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.pathparam.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.api.rs.core.link.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.formparam.locator.JAXRSLocatorClientIT.class) {
            return INVESTIGATE;
        }
        return ConditionEvaluationResult.enabled(null);
    }

    // this prevents automatic imports
    private static class JAXRSClientIT {}
}
