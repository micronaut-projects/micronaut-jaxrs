package io.micronaut.validation.tck;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;
import java.util.Arrays;
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
//        if (testClass != ee.jakarta.tck.ws.rs.ee.rs.core.uriinfo.JAXRSClientIT.class) {
//            return CLIENT;
//        }
        String id = testClass.getName() + "#" + testMethodName;
        if (testClass == ee.jakarta.tck.ws.rs.ee.rs.headerparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.formparam.locator.JAXRSLocatorClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.formparam.sub.JAXRSSubClientIT.class ||
            Set.of(
                "ee.jakarta.tck.ws.rs.ee.rs.get.JAXRSClientIT#dynamicGetTest",
                "ee.jakarta.tck.ws.rs.ee.rs.get.JAXRSClientIT#recursiveResourceLocatorTest",
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
            testClass == ee.jakarta.tck.ws.rs.spec.client.exceptions.ClientExceptionsIT.class ||
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
        } else if (testClass == ee.jakarta.tck.ws.rs.api.rs.core.variantlistbuilder.JAXRSClientIT.class || Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.ext.runtimedelegate.create.JAXRSClientIT#checkCreatedVariantListBuilderTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.variant.JAXRSClientIT#encodingsTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.variant.JAXRSClientIT#languagesTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.variant.JAXRSClientIT#mediaTypesTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("createVariantListBuilder"); // TODO
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.headerparam.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.resource.locator.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.pathparam.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.cookieparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.queryparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.standard.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.pathparam.sub.JAXRSSubClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.cookieparam.locator.JAXRSLocatorClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.pathparam.locator.JAXRSLocatorClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.headerparam.locator.JAXRSLocatorClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.queryparam.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.ee.rs.cookieparam.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.reader.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("request-scoped bean fields");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#readEntityFromBodyTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#readEntityFromHeaderTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#writeBodyEntityUsingWriterTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#writeHeaderEntityUsingWriterTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#isRegisteredMessageBodyReaderWildcardTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#isRegisteredMessageBodyWriterXmlTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#isRegisteredMessageBodReaderXmlTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#isRegisteredMessageBodyWriterWildcardTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#writeIOExceptionUsingWriterTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#readEntityIOExceptionTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#readEntityWebException410Test",
            "ee.jakarta.tck.ws.rs.spec.resource.responsemediatype.JAXRSClientIT#mesageBodyWriterProducesTest",
            "ee.jakarta.tck.ws.rs.spec.provider.visibility.JAXRSClientIT#bodyWriterTest",
            "ee.jakarta.tck.ws.rs.spec.provider.visibility.JAXRSClientIT#bodyReaderTest",
            // Implement proper exception mapping with BC support
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#isRegisteredExceptionMapperNullExceptionTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#isRegisteredIOExceptionExceptionMapperTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#isRegisteredRuntimeExceptionExceptionMapperTest"
            ).contains(id) ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.overridestandard.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("body reader/writer");
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.resource.webappexception.defaultmapper.DefaultExceptionMapperIT.class || Set.of(
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#writeIOExceptionWithoutWriterTest",
            "ee.jakarta.tck.ws.rs.spec.provider.exceptionmapper.JAXRSClientIT#throwableTest",
            "ee.jakarta.tck.ws.rs.spec.provider.exceptionmapper.JAXRSClientIT#clientErrorExceptionTest",
            "ee.jakarta.tck.ws.rs.spec.provider.exceptionmapper.JAXRSClientIT#filterChainTest",
            "ee.jakarta.tck.ws.rs.spec.provider.exceptionmapper.JAXRSClientIT#exceptionTest",
            "ee.jakarta.tck.ws.rs.spec.provider.exceptionmapper.JAXRSClientIT#runtimeExceptionTest",
            "ee.jakarta.tck.ws.rs.spec.provider.exceptionmapper.JAXRSClientIT#webapplicationExceptionTest",
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
            "ee.jakarta.tck.ws.rs.ee.resource.webappexception.mapper.JAXRSClientIT#responseEntityTest",
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
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#isRegisteredAppJsonContextResolverTest",
            "ee.jakarta.tck.ws.rs.ee.rs.ext.providers.JAXRSProvidersClientIT#isRegisteredTextPlainContextResolverTest",
            "ee.jakarta.tck.ws.rs.spec.provider.visibility.JAXRSClientIT#contextResolverTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("getContext");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.link.JAXRSClientIT#fromMethodTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.link.JAXRSClientIT#fromResourceTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.link.JAXRSClientIT#fromResourceWithMediaTypeTest",
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
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.link.JAXRSClientIT#fromPathWithUriTemplateParamsTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.linkbuilder.JAXRSClientIT#buildRelativizedThrowsIAEWhenNotSuppliedValuesTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.linkbuilder.JAXRSClientIT#buildObjectsTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.linkbuilder.JAXRSClientIT#buildRelativizedThrowsIAEWhenSuppliedJustOneValueOutOfThreeTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.linkbuilder.JAXRSClientIT#buildRelativizedThrowsIAEWhenSuppliedValueIsNullTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.linkbuilder.JAXRSClientIT#buildThrowsIAEWhenSuppliedJustOneValueOutOfThreeTest",
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
        } else if (testClass == ee.jakarta.tck.ws.rs.spec.provider.standardwithxmlbinding.JAXRSClientIT.class ||
            testClass == ee.jakarta.tck.ws.rs.spec.provider.jaxbcontext.JAXRSClientIT.class) {
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
            "variantTest",
            "expiresTest"
        ).contains(testMethodName))) {
            return ConditionEvaluationResult.disabled("getLinkBuilder, VariantListBuilder");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT#cookieTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT#getCookiesTest",
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
            "ee.jakarta.tck.ws.rs.api.rs.core.linkbuilder.JAXRSClientIT#buildObjectsThrowsUriBuilderExceptionTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.linkbuilder.JAXRSClientIT#buildNoArgsThrowsUriBuilderExceptionTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.linkbuilder.JAXRSClientIT#buildRelativizedThrowsUriBuilderExceptionTest",
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
            "ee.jakarta.tck.ws.rs.servlet3.rs.core.streamingoutput.JAXRSClientIT#writeTest",
            "ee.jakarta.tck.ws.rs.servlet3.rs.core.streamingoutput.JAXRSClientIT#writeWebApplicationExceptionTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("StreamingOutput"); // todo
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.core.responseclient.JAXRSClientIT#cloneTest",
            "ee.jakarta.tck.ws.rs.api.rs.core.responsebuilder.BuilderClientIT#cloneTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("ResponseBuilder.clone"); // todo
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.spec.resource.responsemediatype.JAXRSClientIT#clientAnyPreferenceTest",
            "ee.jakarta.tck.ws.rs.spec.resource.responsemediatype.JAXRSClientIT#textPreferenceTest",
            "ee.jakarta.tck.ws.rs.spec.resource.responsemediatype.JAXRSClientIT#defaultResponseErrorTest",
            "ee.jakarta.tck.ws.rs.spec.resource.responsemediatype.JAXRSClientIT#clientImagePreferenceTest",
            "ee.jakarta.tck.ws.rs.spec.resource.responsemediatype.JAXRSClientIT#defaultErrorTest",
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#qualityOfSourceOnDifferentMediaTypesTest",
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#concreteOverStarWhenAcceptStarTest",
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT#producesOverridesDescendantSubResourcePathValueWeightTest",
            "ee.jakarta.tck.ws.rs.spec.resource.responsemediatype.JAXRSClientIT#noPreferenceTest",
            "ee.jakarta.tck.ws.rs.spec.resource.responsemediatype.JAXRSClientIT#imagePreferenceTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("media type behavior");
        } else if (testClass == ee.jakarta.tck.ws.rs.jaxrs40.ee.rs.core.uriinfo.UriInfo40ClientIT.class) {
            return ConditionEvaluationResult.disabled("path param issues");
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.formparam.JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("@Encoded");
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.core.securitycontext.basic.JAXRSBasicClientIT.class) {
            return ConditionEvaluationResult.disabled("security context test issues, probably just need a harness fix");
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.core.request.JAXRSClientIT.class || Set.of(
            "ee.jakarta.tck.ws.rs.spec.resource.responsemediatype.JAXRSClientIT#clientHtmlXmlPreferenceTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("@Context on route method parameter");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.spec.resource.responsemediatype.JAXRSClientIT#classProducesTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("Content type in HEAD response, our behavior is fine");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.ee.rs.core.uriinfo.JAXRSClientIT#getMatchedURIsTest",
            "ee.jakarta.tck.ws.rs.ee.rs.core.uriinfo.JAXRSClientIT#getMatchedURIsTest1",
            "ee.jakarta.tck.ws.rs.ee.rs.core.uriinfo.JAXRSClientIT#getMatchedURIsTest2",
            "ee.jakarta.tck.ws.rs.ee.rs.core.uriinfo.JAXRSClientIT#getMatchedResourcesTest",
            "ee.jakarta.tck.ws.rs.ee.rs.core.uriinfo.JAXRSClientIT#requestURITest",
            "ee.jakarta.tck.ws.rs.ee.rs.core.uriinfo.JAXRSClientIT#aPathTest",
            "ee.jakarta.tck.ws.rs.ee.rs.core.uriinfo.JAXRSClientIT#baseUriTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("UriInfo unsupported methods");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.spec.provider.sort.JAXRSClientIT#contentTypeTextXmlGotTextWildCardTest",
            "ee.jakarta.tck.ws.rs.spec.provider.sort.JAXRSClientIT#contentTypeTextHmtlGotTextWildCardTest",
            "ee.jakarta.tck.ws.rs.spec.provider.sort.JAXRSClientIT#contentTypeApplicationGotWildCardTest",
            "ee.jakarta.tck.ws.rs.spec.provider.sort.JAXRSClientIT#contentTypeTextPlainGotTextPlainTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("StringBean");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.spec.template.JAXRSClientIT#Test1",
            "ee.jakarta.tck.ws.rs.spec.template.JAXRSClientIT#Test2"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("routing issues");
        } else if (Set.of(
            "ee.jakarta.tck.ws.rs.api.rs.ext.runtimedelegate.create.JAXRSClientIT#createEndpointThrowsIllegalArgumentExceptionTest"
        ).contains(id)) {
            return ConditionEvaluationResult.disabled("createEndpoint() unsupported");
        } else if (testClass == ee.jakarta.tck.ws.rs.spec.contextprovider.JsonbContextProviderIT.class) {
            return ConditionEvaluationResult.disabled("Arquillian issue, but test is likely broken anyway because we don't use jsonb");
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.resource.webappexception.nomapper .JAXRSClientIT.class) {
            return ConditionEvaluationResult.disabled("Implement proper exception mapping with BC support");
        }
        return ConditionEvaluationResult.enabled(null);
    }

    // this prevents automatic imports
    private static class JAXRSClientIT {}
}
