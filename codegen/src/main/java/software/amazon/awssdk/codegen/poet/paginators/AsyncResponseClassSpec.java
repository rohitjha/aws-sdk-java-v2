/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.codegen.poet.paginators;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.codegen.model.intermediate.IntermediateModel;
import software.amazon.awssdk.codegen.model.intermediate.MemberModel;
import software.amazon.awssdk.codegen.model.service.PaginatorDefinition;
import software.amazon.awssdk.codegen.poet.ClassSpec;
import software.amazon.awssdk.codegen.poet.PoetUtils;
import software.amazon.awssdk.core.pagination.async.AsyncPageFetcher;
import software.amazon.awssdk.core.pagination.async.EmptySubscription;
import software.amazon.awssdk.core.pagination.async.PaginatedItemsPublisher;
import software.amazon.awssdk.core.pagination.async.ResponsesSubscription;
import software.amazon.awssdk.core.pagination.async.SdkPublisher;

/**
 * Java poet {@link ClassSpec} to generate the response class for async paginated operations.
 */
public class AsyncResponseClassSpec extends PaginatorsClassSpec {

    private static final String SUBSCRIBER = "subscriber";
    private static final String SUBSCRIBE_METHOD = "subscribe";
    private static final String LAST_PAGE_FIELD = "isLastPage";
    private static final String LAST_PAGE_METHOD = "withLastPage";

    public AsyncResponseClassSpec(IntermediateModel model, String c2jOperationName, PaginatorDefinition paginatorDefinition) {
        super(model, c2jOperationName, paginatorDefinition);
    }

    @Override
    public TypeSpec poetSpec() {
        TypeSpec.Builder specBuilder = TypeSpec.classBuilder(className())
                                               .addModifiers(Modifier.PUBLIC)
                                               .addAnnotation(PoetUtils.GENERATED)
                                               .addSuperinterface(getAsyncResponseInterface())
                                               .addFields(Stream.of(asyncClientInterfaceField(),
                                                                    requestClassField(),
                                                                    asyncPageFetcherField(),
                                                                    lastPageField())
                                                                .collect(Collectors.toList()))
                                               .addMethod(constructor())
                                               .addMethod(subscribeMethod())
                                               .addMethods(getMethodSpecsForResultKeyList())
                                               .addMethod(resumeMethod())
                                               .addMethod(lastPageMethod())
                                               .addJavadoc(paginationDocs.getDocsForAsyncResponseClass(
                                                   getAsyncClientInterfaceName()))
                                               .addType(nextPageFetcherClass());

        return specBuilder.build();
    }

    @Override
    public ClassName className() {
        return poetExtensions.getResponseClassForPaginatedAsyncOperation(c2jOperationName);
    }

    /**
     * Returns the interface that is implemented by the Paginated Async Response class.
     */
    private TypeName getAsyncResponseInterface() {
        return ParameterizedTypeName.get(ClassName.get(SdkPublisher.class), responseType());
    }

    /**
     * @return A Poet {@link ClassName} for the async client interface
     */
    private ClassName getAsyncClientInterfaceName() {
        return poetExtensions.getClientClass(model.getMetadata().getAsyncInterface());
    }

    private FieldSpec asyncClientInterfaceField() {
        return FieldSpec.builder(getAsyncClientInterfaceName(), CLIENT_MEMBER, Modifier.PRIVATE, Modifier.FINAL).build();
    }

    private FieldSpec asyncPageFetcherField() {
        return FieldSpec.builder(AsyncPageFetcher.class, NEXT_PAGE_FETCHER_MEMBER, Modifier.PRIVATE, Modifier.FINAL).build();
    }

    private FieldSpec lastPageField() {
        return FieldSpec.builder(boolean.class, LAST_PAGE_FIELD, Modifier.PRIVATE).build();
    }

    private MethodSpec constructor() {
        return MethodSpec.constructorBuilder()
                         .addModifiers(Modifier.PUBLIC)
                         .addParameter(getAsyncClientInterfaceName(), CLIENT_MEMBER, Modifier.FINAL)
                         .addParameter(requestType(), REQUEST_MEMBER, Modifier.FINAL)
                         .addStatement("this.$L = $L", CLIENT_MEMBER, CLIENT_MEMBER)
                         .addStatement("this.$L = $L", REQUEST_MEMBER, REQUEST_MEMBER)
                         .addStatement("this.$L = new $L()", NEXT_PAGE_FETCHER_MEMBER, nextPageFetcherClassName())
                         .build();
    }

    /**
     * A {@link MethodSpec} for the subscribe() method which is inherited from the interface.
     */
    private MethodSpec subscribeMethod() {
        return MethodSpec.methodBuilder(SUBSCRIBE_METHOD)
                         .addAnnotation(Override.class)
                         .addModifiers(Modifier.PUBLIC)
                         .addParameter(ParameterizedTypeName.get(ClassName.get(Subscriber.class),
                                                                 WildcardTypeName.supertypeOf(responseType())),
                                       SUBSCRIBER)
                         .addStatement("$L.onSubscribe(new $T($L, $L))", SUBSCRIBER, ResponsesSubscription.class,
                                       SUBSCRIBER, NEXT_PAGE_FETCHER_MEMBER)
                         .build();
    }

    /**
     * Returns iterable of {@link MethodSpec} to generate helper methods for all members
     * in {@link PaginatorDefinition#getResultKey()}.
     *
     * The helper methods return a publisher that can be used to stream over the collection of result keys.
     * These methods will only be generated if {@link PaginatorDefinition#getResultKey()} is not null and a non-empty list.
     */
    private Iterable<MethodSpec> getMethodSpecsForResultKeyList() {
        if (paginatorDefinition.getResultKey() != null) {
            return paginatorDefinition.getResultKey().stream()
                                      .map(this::getMethodsSpecForSingleResultKey)
                                      .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /*
     * Generate a method spec for single element in {@link PaginatorDefinition#getResultKey()} list.
     *
     * If the element is "Folders" and its type is "List<FolderMetadata>", generated code looks like:
     *
     *  public SdkPublisher<FolderMetadata> folders() {
     *      Function<DescribeFolderContentsResponse, Iterator<FolderMetadata>> getIterator = response -> {
     *          if (response != null && response.folders() != null) {
     *              return response.folders().iterator();
     *          }
     *          return Collections.emptyIterator();
     *      };
     *      return new PaginatedItemsPublisher(new DescribeFolderContentsResponseFetcher(), getIterator);
     *  }
     */
    private MethodSpec getMethodsSpecForSingleResultKey(String resultKey) {
        TypeName resultKeyType = getTypeForResultKey(resultKey);
        MemberModel resultKeyModel = memberModelForResponseMember(resultKey);

        return MethodSpec.methodBuilder(resultKeyModel.getFluentGetterMethodName())
                         .addModifiers(Modifier.PUBLIC)
                         .returns(ParameterizedTypeName.get(ClassName.get(SdkPublisher.class), resultKeyType))
                         .addCode("$T getIterator = ",
                                  ParameterizedTypeName.get(ClassName.get(Function.class),
                                                            responseType(),
                                                            ParameterizedTypeName.get(ClassName.get(Iterator.class),
                                                                                      resultKeyType)))
                         .addCode(getIteratorLambdaBlock(resultKey, resultKeyModel))
                         .addCode("\n")
                         .addStatement("return new $T(new $L(), getIterator, $L)",
                                       PaginatedItemsPublisher.class,
                                       nextPageFetcherClassName(),
                                       LAST_PAGE_FIELD)
                         .addJavadoc(CodeBlock.builder()
                                              .add("Returns a publisher that can be used to get a stream of data. You need to "
                                                   + "subscribe to the publisher to request the stream of data. The publisher "
                                                   + "has a helper forEach method that takes in a {@link $T} and then applies "
                                                   + "that consumer to each response returned by the service.",
                                                   TypeName.get(Consumer.class))
                                              .build())
                         .build();
    }

    /**aW
     * Generates a inner class that implements {@link AsyncPageFetcher}. This is a helper class that can be used
     * to find if there are more pages in the response and to get the next page if exists.
     */
    private TypeSpec nextPageFetcherClass() {
        return TypeSpec.classBuilder(nextPageFetcherClassName())
                       .addModifiers(Modifier.PRIVATE)
                       .addSuperinterface(ParameterizedTypeName.get(ClassName.get(AsyncPageFetcher.class), responseType()))
                       .addMethod(MethodSpec.methodBuilder(HAS_NEXT_PAGE_METHOD)
                                            .addModifiers(Modifier.PUBLIC)
                                            .addAnnotation(Override.class)
                                            .addParameter(responseType(), PREVIOUS_PAGE_METHOD_ARGUMENT, Modifier.FINAL)
                                            .returns(boolean.class)
                                            .addStatement(hasNextPageMethodBody())
                                            .build())
                       .addMethod(MethodSpec.methodBuilder(NEXT_PAGE_METHOD)
                                            .addModifiers(Modifier.PUBLIC)
                                            .addAnnotation(Override.class)
                                            .addParameter(responseType(), PREVIOUS_PAGE_METHOD_ARGUMENT, Modifier.FINAL)
                                            .returns(ParameterizedTypeName.get(ClassName.get(CompletableFuture.class),
                                                                               responseType()))
                                            .addCode(nextPageMethodBody())
                                            .build())
                       .build();
    }

    private MethodSpec resumeMethod() {
        return resumeMethodBuilder().addCode(CodeBlock.builder()
                                                      .addStatement("return $L.$L(true)", anonymousClassWithEmptySubscription(),
                                                                    LAST_PAGE_METHOD)
                                                      .build())
                                    .build();
    }

    private TypeSpec anonymousClassWithEmptySubscription() {
        return TypeSpec.anonymousClassBuilder("$L, $L", CLIENT_MEMBER, REQUEST_MEMBER)
                       .addSuperinterface(className())
                       .addMethod(MethodSpec.methodBuilder(SUBSCRIBE_METHOD)
                                            .addAnnotation(Override.class)
                                            .addModifiers(Modifier.PUBLIC)
                                            .addParameter(ParameterizedTypeName.get(ClassName.get(Subscriber.class),
                                                                                    WildcardTypeName.supertypeOf(responseType())),
                                                          SUBSCRIBER)
                                            .addStatement("$L.onSubscribe(new $T($L))", SUBSCRIBER,
                                                          TypeName.get(EmptySubscription.class), SUBSCRIBER)
                                            .build())
                       .build();
    }

    private MethodSpec lastPageMethod() {
        return MethodSpec.methodBuilder(LAST_PAGE_METHOD)
                         .returns(className())
                         .addParameter(boolean.class, LAST_PAGE_FIELD)
                         .addStatement("this.$L = $L", LAST_PAGE_FIELD, LAST_PAGE_FIELD)
                         .addStatement("return this")
                         .build();
    }

}