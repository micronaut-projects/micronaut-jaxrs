package io.micronaut.validation.tck;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

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
        String id = testClass.getName() + "#" + testMethodName;
        if (testClass == ee.jakarta.tck.ws.rs.ee.rs.get.JAXRSClientIT.class) {
            switch (testMethodName) {
                case "dynamicGetTest", "recursiveResourceLocatorTest" -> {
                    return SUBRESOURCES;
                }
            }
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.headerparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.formparam.locator.JAXRSLocatorClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.formparam.sub.JAXRSSubClientIT.class ||
            Set.of(
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#consumesCorrectContentTypeOnResourceLocatorTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#producesCorrectContentTypeOnResourceLocatorTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#consumesOnSubResourceLocatorTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#l2SubResourceLocatorTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#producesOnResourceLocatorTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#producesCorrectContentTypeOnSubResourceLocatorTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#requestNotSupportedOnSubResourceLocatorTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#requestNotSupportedOnResourceLocatorTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#producesOnSubResourceLocatorTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#consumesCorrectContentTypeOnSubResourceLocatorTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#foundAnotherResourceLocatorByPathTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#consumesOnResourceLocatorTest"
            ).contains(id)) {
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
            testClass == ee.jakarta.tck.ws.rs.spec.resource.locator.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.pathparam.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.cookieparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.queryparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.standard.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.reader.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("request-scoped bean fields");
        } else if ((testClass == ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT.class && Arrays.asList(
            "readEntityFromBodyTest",
            "readEntityFromHeaderTest",
            "writeBodyEntityUsingWriterTest",
            "writeHeaderEntityUsingWriterTest",
            "isRegisteredMessageBodyReaderWildcardTest",
            "isRegisteredMessageBodyWriterXmlTest",
            "isRegisteredMessageBodReaderXmlTest",
            "isRegisteredMessageBodyWriterWildcardTest",
            "writeIOExceptionUsingWriterTest",
            "readEntityIOExceptionTest",
            "readEntityWebException410Test"
        ).contains(testMethodName)) || Set.of(
            "ee.jakarta.tck.ws.rs.spec.provider.visibility.JAXRSClientIT#bodyWriterTest",
            "ee.jakarta.tck.ws.rs.spec.provider.visibility.JAXRSClientIT#bodyReaderTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("body reader/writer");
        } else if ((testClass == ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT.class && Objects.equals("writeIOExceptionWithoutWriterTest", testMethodName)) ||
            testClass == ee.jakarta.tck.ws.rs.ee.resource.webappexception.defaultmapper.DefaultExceptionMapperIT.class ||
            Set.of(
                "ee.jakarta.tck.ws.rs.jaxrs21.ee.priority.JAXRSClientIT#exceptionMapperPriorityTest",
                "ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT#statusOkResponseTest",
                "ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT#throwUncheckedExceptionTest",
                "ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT#throwableIntOkResponseTest",
                "ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT#throwableOkResponseTest",
                "ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT#statusIntOkResponseTest",
                "ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT#throwableResponseTest",
                "ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT#noResponseTest",
                "ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT#webApplicationExceptionHasResponseWithoutEntityDoesUseMapperTest",
                "ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT#okResponseTest",
                "ee.jakarta.tck.ws.rs.spec.provider.visibility.JAXRSClientIT#exceptionMapperTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#slashWrongUriTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#requestNotSupportedOnResourceTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#producesOnResourceTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#requestNotSupportedOnSubResourceTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#locatorNameTooLongTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#methodNotFoundTest",
                "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#consumesOnResourceTest"
            ).contains(id)) {
            return ConditionEvaluationResult.disabled("exception mappers");
        } else if ((testClass == ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT.class && Arrays.asList(
            "isRegisteredAppJsonContextResolverTest",
            "isRegisteredTextPlainContextResolverTest"
        ).contains(testMethodName)) || Set.of(
            "ee.jakarta.tck.ws.rs.spec.provider.visibility.JAXRSClientIT#contextResolverTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("getContext");
        } else if ((testClass == ee.jakarta.tck.ws.rs.api.rs.core.link.JAXRSClientIT.class && Arrays.asList(
            "fromMethodTest",
            "fromResourceTest",
            "fromResourceWithMediaTypeTest"
        ).contains(testMethodName)) || Set.of(
            "ee.jakarta.tck.ws.rs.uribuilder.UriBuilderIT#emptyUriBuilderBuildsEmptyUri",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#fragmentTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#queryParamTest4",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#queryParamTest5",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#fromPathTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#cloneTest1",
            "ee.jakarta.tck.ws.rs.uribuilder.UriBuilderIT#shouldBuildValidInstanceFromScratch"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("relative UriBuilder");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.uribuilder.UriBuilderIT#shouldThrowUriBuilderExceptionOnSchemeOnlyUri",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#hostTest2"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("UriBuilder exceptions");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT#webApplicationExceptionHasResponseWithEntityDoesNotUseMapperTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("missing method annotation");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#locatorNameTooLongAgainTest",
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#consumesOverridesDescendantSubResourcePathValueTest",
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#slashUriTest",
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#descendantResourcePathValueTest",
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#producesOverridesDescendantSubResourcePathValuePostTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("matching weirdness");
        } else if ((testClass == ee.jakarta.tck.ws.rs.api.rs.core.link.JAXRSClientIT.class && Arrays.asList(
            "fromPathWithUriTemplateParamsTest"
        ).contains(testMethodName)) || Set.of(
            "ee.jakarta.tck.ws.rs.uribuilder.UriBuilderIT#shouldThrowIllegalArgumentExceptionForUnresolvedTemplates",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplateStringObjectBooleanSlashNotEncodedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesMapThrowsIAEOnNullNameTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesMapBooleanSlashNotEncodedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildObjectsBooleanThrowsIAEWhenNoValueSuppliedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplateFromEncodedPercentEncodedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesFromEncodedPercentEncodedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesMapThrowsIAEOnNullValueTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplateStringObjectBooleanThrowsIAEOnNullValueTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplateStringObjectBooleanSlashEncodedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplateStringObjectThrowsIAEOnNullValueTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesMapBooleanThrowsIAEOnNullNameTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#templateTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#templateTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplateFromEncodedThrowsNullOnNullValueTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplateFromEncodedThrowsNullOnNullNameTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesMapBooleanThrowsIAEOnNullMapTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesFromEncodedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesFromEncodedThrowsNullOnNullValueTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesFromEncodedThrowsNullOnNullNameTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildObjectsBooleanEncodedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesFromEncodedThrowsNullOnNullMapTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesMapBooleanSlashEncodedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesMapBooleanThrowsIAEOnNullValueTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplateStringObjectTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplateStringObjectThrowsIAEOnNullNameTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#fromEncodedTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#fromEncodedTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#fromEncodedTest3",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildObjectsBooleanNotEncodedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplateFromEncodedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildObjectsBooleanThrowsIAEWhenValueIsNullTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplateStringObjectBooleanThrowsIAEOnNullNameTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#resolveTemplatesMapTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#toTemplateTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("uri template");
        } else if (testClass == ee.jakarta.tck.ws.rs.spec.provider.standardwithxmlbinding.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("JAXB");
        } else if (((testClass == ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT.class || testClass == ee.jakarta.tck.ws.rs.api.rs.core.responseclient.JAXRSClientIT.class) && Arrays.asList(
            "getLinkBuilderForTheRelationTest",
            "getLinksTest",
            "okTest5",
            "fromResponseTest",
            "getLinkTest",
            "notAcceptableTest",
            "hasLinkWhenLinkTest",
            "variantsTest",
            "linksTest",
            "linkStringStringTest",
            "linkUriStringTest",
            "variantTest"
        ).contains(testMethodName))) {
            return ConditionEvaluationResult.disabled("getLinkBuilder, VariantListBuilder");
        } else if ((testClass == ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT.class && Arrays.asList(
            "cookieTest",
            "getCookiesTest"
        ).contains(testMethodName)) || Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.responseclient.JAXRSClientIT#cookieTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.responseclient.JAXRSClientIT#getCookiesTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("cookies do not contain version=1"); // todo: should we do this?
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT#tagTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.responseclient.JAXRSClientIT#tagTest2"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("duplicate ETAG (should be relatively easy)"); // todo
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#uriStringTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#schemeSpecificPartTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#schemeSpecificPartTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#fromUriTest3",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#fromUriTest4",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#uriTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("UriBuilder scheme specific part");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceQueryParamTest2"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("remove query params");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.jaxrs21.ee.priority.JAXRSClientIT#paramConverterPriorityTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("param converters");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceQueryTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceQueryTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceQueryTest3"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("replaceQuery with string");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceMatrixParamTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceMatrixParamTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceMatrixParamTest3",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceMatrixTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceMatrixTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceMatrixTest3",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#matrixParamTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#matrixParamTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#matrixParamTest3",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceMatrixParamTest4"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("UriBuilder matrixParam");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#replaceQueryParamTest3",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromMapTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromMapTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromMapTest5",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromMapWithBooleanSlashNotEncodedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#pathTest0",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#pathTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#segmentTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#segmentTest3",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#pathTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromEncodedMapTest1",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromEncodedMapTest2",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromEncodedMapTest3",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromEncodedMapTest4",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromEncodedMapTest5",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromMapWithBooleanSlashEncodedTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("UriBuilder encoding behavior");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromMapWithBooleanThrowsIAEWhenNoSuppliedValueTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.uribuilder.JAXRSClientIT#buildFromMapTest4"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("missing template variable");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT#acceptedGenericEntityTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.responseclient.JAXRSClientIT#acceptedGenericEntityTest",
            "ee.jakarta.tck.ws.rs.ee.resource.java2entity.JAXRSClientIT#genericEntityTest",
            "ee.jakarta.tck.ws.rs.ee.resource.java2entity.JAXRSClientIT#responseGenericEntityTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("GenericEntity serializer"); // todo
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.ee.resource.java2entity.JAXRSClientIT#directClassTypeTest",
            "ee.jakarta.tck.ws.rs.ee.resource.java2entity.JAXRSClientIT#responseDirectClassTypeTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("Encoder for */*"); // todo
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT#getLanguageTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.responseclient.JAXRSClientIT#getLanguageTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("getLocale has different case"); // todo
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#optionsOnSubResourceTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("header has different case"); // todo
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT#getDateTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT#getLastModifiedTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.responseclient.JAXRSClientIT#getDateTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.responseclient.JAXRSClientIT#getLastModifiedTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("Date headers have incorrect format (TZ, comma)"); // todo
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT#getCookiesIsImmutableTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.responseclient.JAXRSClientIT#getCookiesIsImmutableTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("getCookies"); // todo
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#qualityOfSourceOnDifferentMediaTypesTest",
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#concreteOverStarWhenAcceptStarTest",
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#producesOverridesDescendantSubResourcePathValueWeightTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("media type behavior");
        } else if (testClass == ee.jakarta.tck.ws.rs.jaxrs40.ee.rs.core.uriinfo.UriInfo40ClientIT.class) {
            return ConditionEvaluationResult.disabled("path param issues");
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.formparam.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("@Encoded");
        } else if (testClass == ee.jakarta.tck.ws.rs.servlet3.rs.applicationpath.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("encoded @ApplicationPath");
        } else if (testClass == ee.jakarta.tck.ws.rs.api.client.entity.JAXRSClientIT.class && testMethodName.equals("entityStringThrowsExceptionWhenNullTest") || testClass == ee.jakarta.tck.ws.rs.api.rs.core.entitytag.JAXRSClientIT.class && testMethodName.equals("valueOfTest") || testClass == ee.jakarta.tck.ws.rs.api.rs.core.cookie.JAXRSClientIT.class && testMethodName.equals("parseTest3") || testClass == ee.jakarta.tck.ws.rs.api.rs.ext.runtimedelegate.create.JAXRSClientIT.class && Arrays.asList(
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
            (testClass == ee.jakarta.tck.ws.rs.api.rs.core.linkbuilder.JAXRSClientIT.class && Arrays.asList(
                "buildRelativizedThrowsIAEWhenNotSuppliedValuesTest",
                "buildObjectsTest",
                "buildNoArgsThrowsUriBuilderExceptionTest",
                "buildRelativizedThrowsUriBuilderExceptionTest",
                "buildRelativizedThrowsIAEWhenSuppliedJustOneValueOutOfThreeTest",
                "buildRelativizedThrowsIAEWhenSuppliedValueIsNullTest",
                "buildThrowsIAEWhenSuppliedJustOneValueOutOfThreeTest",
                "buildObjectsThrowsUriBuilderExceptionTest"
            ).contains(testMethodName))) {
            return INVESTIGATE;
        }
        return ConditionEvaluationResult.enabled(null);
    }

    // this prevents automatic imports
    private static class JAXRSClientIT {}
}
