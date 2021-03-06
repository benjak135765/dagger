/*
 * Copyright (C) 2014 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verifyNotNull;
import static com.squareup.javapoet.ClassName.OBJECT;
import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static com.squareup.javapoet.TypeSpec.classBuilder;
import static dagger.internal.codegen.AnnotationSpecs.Suppression.UNCHECKED;
import static dagger.internal.codegen.CodeBlocks.makeParametersCodeBlock;
import static dagger.internal.codegen.GwtCompatibility.gwtIncompatibleAnnotation;
import static dagger.internal.codegen.SourceFiles.bindingTypeElementTypeVariableNames;
import static dagger.internal.codegen.SourceFiles.frameworkTypeUsageStatement;
import static dagger.internal.codegen.SourceFiles.generateBindingFieldsForDependencies;
import static dagger.internal.codegen.SourceFiles.generatedClassNameForBinding;
import static dagger.internal.codegen.TypeNames.FUTURES;
import static dagger.internal.codegen.TypeNames.PRODUCERS;
import static dagger.internal.codegen.TypeNames.PRODUCER_TOKEN;
import static dagger.internal.codegen.TypeNames.VOID_CLASS;
import static dagger.internal.codegen.TypeNames.listOf;
import static dagger.internal.codegen.TypeNames.listenableFutureOf;
import static dagger.internal.codegen.TypeNames.producedOf;
import static java.util.stream.Collectors.joining;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PROTECTED;
import static javax.lang.model.element.Modifier.PUBLIC;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.model.DependencyRequest;
import dagger.model.Key;
import dagger.model.RequestKind;
import dagger.producers.Producer;
import dagger.producers.internal.AbstractProducesMethodProducer;
import java.util.Map;
import java.util.Optional;
import javax.annotation.processing.Filer;
import javax.inject.Inject;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/**
 * Generates {@link Producer} implementations from {@link ProductionBinding} instances.
 */
final class ProducerFactoryGenerator extends SourceFileGenerator<ProductionBinding> {
  private final CompilerOptions compilerOptions;
  private final KeyFactory keyFactory;

  @Inject
  ProducerFactoryGenerator(
      Filer filer,
      Elements elements,
      SourceVersion sourceVersion,
      CompilerOptions compilerOptions,
      KeyFactory keyFactory) {
    super(filer, elements, sourceVersion);
    this.compilerOptions = compilerOptions;
    this.keyFactory = keyFactory;
  }

  @Override
  ClassName nameGeneratedType(ProductionBinding binding) {
    return generatedClassNameForBinding(binding);
  }

  @Override
  Element originatingElement(ProductionBinding binding) {
    // we only create factories for bindings that have a binding element
    return binding.bindingElement().get();
  }

  @Override
  Optional<TypeSpec.Builder> write(ClassName generatedTypeName, ProductionBinding binding) {
    // We don't want to write out resolved bindings -- we want to write out the generic version.
    checkArgument(!binding.unresolved().isPresent());
    checkArgument(binding.bindingElement().isPresent());

    TypeName providedTypeName = TypeName.get(binding.contributedType());
    TypeName futureTypeName = listenableFutureOf(providedTypeName);

    TypeSpec.Builder factoryBuilder =
        classBuilder(generatedTypeName)
            .addAnnotation(
                // TODO(beder): examine if we can remove this or prevent subtypes of Future from
                // being produced
                AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "FutureReturnValueIgnored")
                    .build())
            .addModifiers(PUBLIC, FINAL)
            .addTypeVariables(bindingTypeElementTypeVariableNames(binding));

    UniqueNameSet uniqueFieldNames = new UniqueNameSet();
    ImmutableMap.Builder<Key, FieldSpec> fieldsBuilder = ImmutableMap.builder();

    MethodSpec.Builder constructorBuilder = constructorBuilder().addModifiers(PUBLIC);

    Optional<FieldSpec> moduleField =
        binding.requiresModuleInstance()
            ? Optional.of(
                addFieldAndConstructorParameter(
                    factoryBuilder,
                    constructorBuilder,
                    uniqueFieldNames.getUniqueName("module"),
                    TypeName.get(binding.bindingTypeElement().get().asType())))
            : Optional.empty();

    String executorParameterName = null;
    String monitorParameterName = null;
    for (Map.Entry<Key, FrameworkField> entry :
        generateBindingFieldsForDependencies(binding).entrySet()) {
      Key key = entry.getKey();
      FrameworkField bindingField = entry.getValue();
      String fieldName = uniqueFieldNames.getUniqueName(bindingField.name());
      if (key.equals(keyFactory.forProductionImplementationExecutor())) {
        executorParameterName = fieldName;
        constructorBuilder.addParameter(bindingField.type(), executorParameterName);
        continue;
      } else if (key.equals(keyFactory.forProductionComponentMonitor())) {
        monitorParameterName = fieldName;
        constructorBuilder.addParameter(bindingField.type(), monitorParameterName);
        continue;
      }
      FieldSpec field =
          addFieldAndConstructorParameter(
              factoryBuilder, constructorBuilder, fieldName, bindingField.type());
      fieldsBuilder.put(key, field);
    }
    ImmutableMap<Key, FieldSpec> fields = fieldsBuilder.build();

    constructorBuilder.addStatement(
        "super($N, $L, $N)",
        verifyNotNull(monitorParameterName),
        producerTokenConstruction(generatedTypeName, binding),
        verifyNotNull(executorParameterName));

    if (binding.requiresModuleInstance()) {
      assignField(constructorBuilder, moduleField.get());
    }
    
    for (FieldSpec field : fields.values()) {
      assignField(constructorBuilder, field);
    }

    MethodSpec.Builder collectDependenciesBuilder =
        methodBuilder("collectDependencies")
            .addAnnotation(Override.class)
            .addModifiers(PROTECTED);

    ImmutableList<DependencyRequest> asyncDependencies = asyncDependencies(binding);
    for (DependencyRequest dependency : asyncDependencies) {
      TypeName futureType = listenableFutureOf(asyncDependencyType(dependency));
      CodeBlock futureAccess = CodeBlock.of("$N.get()", fields.get(dependency.key()));
      collectDependenciesBuilder.addStatement(
          "$T $L = $L",
          futureType,
          dependencyFutureName(dependency),
          dependency.kind().equals(RequestKind.PRODUCED)
              ? CodeBlock.of("$T.createFutureProduced($L)", PRODUCERS, futureAccess)
              : futureAccess);
    }
    FutureTransform futureTransform = FutureTransform.create(fields, binding, asyncDependencies);

    collectDependenciesBuilder
        .returns(listenableFutureOf(futureTransform.applyArgType()))
        .addStatement("return $L", futureTransform.futureCodeBlock());

    MethodSpec.Builder callProducesMethod =
        methodBuilder("callProducesMethod")
            .returns(futureTypeName)
            .addAnnotation(Override.class)
            .addModifiers(PUBLIC)
            .addParameter(futureTransform.applyArgType(), futureTransform.applyArgName())
            .addExceptions(getThrownTypeNames(binding.thrownTypes()))
            .addCode(
                getInvocationCodeBlock(
                    binding, providedTypeName, futureTransform.parameterCodeBlocks()));
    if (futureTransform.hasUncheckedCast()) {
      callProducesMethod.addAnnotation(AnnotationSpecs.suppressWarnings(UNCHECKED));
    }

    factoryBuilder
        .superclass(
            ParameterizedTypeName.get(
                ClassName.get(AbstractProducesMethodProducer.class),
                futureTransform.applyArgType(),
                providedTypeName))
        .addMethod(constructorBuilder.build())
        .addMethod(collectDependenciesBuilder.build())
        .addMethod(callProducesMethod.build());

    gwtIncompatibleAnnotation(binding).ifPresent(factoryBuilder::addAnnotation);

    // TODO(gak): write a sensible toString
    return Optional.of(factoryBuilder);
  }

  // TODO(ronshapiro): consolidate versions of these
  private static FieldSpec addFieldAndConstructorParameter(
      TypeSpec.Builder typeBuilder,
      MethodSpec.Builder constructorBuilder,
      String variableName,
      TypeName variableType) {
    FieldSpec field = FieldSpec.builder(variableType, variableName, PRIVATE, FINAL).build();
    typeBuilder.addField(field);
    constructorBuilder.addParameter(field.type, field.name);
    return field;
  }

  private static void assignField(MethodSpec.Builder constructorBuilder, FieldSpec field) {
    constructorBuilder.addStatement("this.$1N = $1N", field);
  }

  /** Returns a list of dependencies that are generated asynchronously. */
  private static ImmutableList<DependencyRequest> asyncDependencies(Binding binding) {
    final ImmutableMap<DependencyRequest, FrameworkDependency> frameworkDependencies =
        binding.dependenciesToFrameworkDependenciesMap();
    return FluentIterable.from(binding.dependencies())
        .filter(
            dependency ->
                isAsyncDependency(dependency)
                    && frameworkDependencies
                        .get(dependency)
                        .frameworkClass()
                        .equals(Producer.class))
        .toList();
  }

  private CodeBlock producerTokenConstruction(
      ClassName generatedTypeName, ProductionBinding binding) {
    CodeBlock producerTokenArgs =
        compilerOptions.writeProducerNameInToken()
            ? CodeBlock.of(
                "$S",
                String.format(
                    "%s#%s",
                    ClassName.get(binding.bindingTypeElement().get()),
                    binding.bindingElement().get().getSimpleName()))
            : CodeBlock.of("$T.class", generatedTypeName);
    return CodeBlock.of("$T.create($L)", PRODUCER_TOKEN, producerTokenArgs);
  }

  /** Returns a name of the variable representing this dependency's future. */
  private static String dependencyFutureName(DependencyRequest dependency) {
    return dependency.requestElement().get().getSimpleName() + "Future";
  }

  /** Represents the transformation of an input future by a producer method. */
  abstract static class FutureTransform {
    protected final ImmutableMap<Key, FieldSpec> fields;
    protected final ProductionBinding binding;

    FutureTransform(ImmutableMap<Key, FieldSpec> fields, ProductionBinding binding) {
      this.fields = fields;
      this.binding = binding;
    }

    /** The code block representing the future that should be transformed. */
    abstract CodeBlock futureCodeBlock();

    /** The type of the argument to the apply method. */
    abstract TypeName applyArgType();

    /** The name of the argument to the apply method */
    abstract String applyArgName();

    /** The code blocks to be passed to the produces method itself. */
    abstract ImmutableList<CodeBlock> parameterCodeBlocks();

    /** Whether the transform method has an unchecked cast. */
    boolean hasUncheckedCast() {
      return false;
    }

    static FutureTransform create(
        ImmutableMap<Key, FieldSpec> fields,
        ProductionBinding binding,
        ImmutableList<DependencyRequest> asyncDependencies) {
      if (asyncDependencies.isEmpty()) {
        return new NoArgFutureTransform(fields, binding);
      } else if (asyncDependencies.size() == 1) {
        return new SingleArgFutureTransform(
            fields, binding, Iterables.getOnlyElement(asyncDependencies));
      } else {
        return new MultiArgFutureTransform(fields, binding, asyncDependencies);
      }
    }
  }

  static final class NoArgFutureTransform extends FutureTransform {
    NoArgFutureTransform(ImmutableMap<Key, FieldSpec> fields, ProductionBinding binding) {
      super(fields, binding);
    }

    @Override
    CodeBlock futureCodeBlock() {
      return CodeBlock.of("$T.<$T>immediateFuture(null)", FUTURES, VOID_CLASS);
    }

    @Override
    TypeName applyArgType() {
      return VOID_CLASS;
    }

    @Override
    String applyArgName() {
      return "ignoredVoidArg";
    }

    @Override
    ImmutableList<CodeBlock> parameterCodeBlocks() {
      ImmutableList.Builder<CodeBlock> parameterCodeBlocks = ImmutableList.builder();
      for (DependencyRequest dependency : binding.explicitDependencies()) {
        parameterCodeBlocks.add(
            frameworkTypeUsageStatement(
                CodeBlock.of("$N", fields.get(dependency.key())), dependency.kind()));
      }
      return parameterCodeBlocks.build();
    }
  }

  static final class SingleArgFutureTransform extends FutureTransform {
    private final DependencyRequest asyncDependency;

    SingleArgFutureTransform(
        ImmutableMap<Key, FieldSpec> fields,
        ProductionBinding binding,
        DependencyRequest asyncDependency) {
      super(fields, binding);
      this.asyncDependency = asyncDependency;
    }

    @Override
    CodeBlock futureCodeBlock() {
      return CodeBlock.of("$L", dependencyFutureName(asyncDependency));
    }

    @Override
    TypeName applyArgType() {
      return asyncDependencyType(asyncDependency);
    }

    @Override
    String applyArgName() {
      String argName = asyncDependency.requestElement().get().getSimpleName().toString();
      if (argName.equals("module")) {
        return "moduleArg";
      }
      return argName;
    }

    @Override
    ImmutableList<CodeBlock> parameterCodeBlocks() {
      ImmutableList.Builder<CodeBlock> parameterCodeBlocks = ImmutableList.builder();
      for (DependencyRequest dependency : binding.explicitDependencies()) {
        // We really want to compare instances here, because asyncDependency is an element in the
        // set binding.dependencies().
        if (dependency == asyncDependency) {
          parameterCodeBlocks.add(CodeBlock.of("$L", applyArgName()));
        } else {
          parameterCodeBlocks.add(
              // TODO(ronshapiro) extract this into a method shared by FutureTransform subclasses
              frameworkTypeUsageStatement(
                  CodeBlock.of("$N", fields.get(dependency.key())), dependency.kind()));
        }
      }
      return parameterCodeBlocks.build();
    }
  }

  static final class MultiArgFutureTransform extends FutureTransform {
    private final ImmutableList<DependencyRequest> asyncDependencies;

    MultiArgFutureTransform(
        ImmutableMap<Key, FieldSpec> fields,
        ProductionBinding binding,
        ImmutableList<DependencyRequest> asyncDependencies) {
      super(fields, binding);
      this.asyncDependencies = asyncDependencies;
    }

    @Override
    CodeBlock futureCodeBlock() {
      return CodeBlock.of(
          "$T.<$T>allAsList($L)",
          FUTURES,
          OBJECT,
          asyncDependencies
              .stream()
              .map(ProducerFactoryGenerator::dependencyFutureName)
              .collect(joining(", ")));
    }

    @Override
    TypeName applyArgType() {
      return listOf(OBJECT);
    }

    @Override
    String applyArgName() {
      return "args";
    }

    @Override
    ImmutableList<CodeBlock> parameterCodeBlocks() {
      return getParameterCodeBlocks(binding, fields, applyArgName());
    }

    @Override
    boolean hasUncheckedCast() {
      return true;
    }
  }

  private static boolean isAsyncDependency(DependencyRequest dependency) {
    switch (dependency.kind()) {
      case INSTANCE:
      case PRODUCED:
        return true;
      default:
        return false;
    }
  }

  private static TypeName asyncDependencyType(DependencyRequest dependency) {
    TypeName keyName = TypeName.get(dependency.key().type());
    switch (dependency.kind()) {
      case INSTANCE:
        return keyName;
      case PRODUCED:
        return producedOf(keyName);
      default:
        throw new AssertionError();
    }
  }

  private static ImmutableList<CodeBlock> getParameterCodeBlocks(
      ProductionBinding binding, ImmutableMap<Key, FieldSpec> fields, String listArgName) {
    int argIndex = 0;
    ImmutableList.Builder<CodeBlock> codeBlocks = ImmutableList.builder();
    for (DependencyRequest dependency : binding.explicitDependencies()) {
      if (isAsyncDependency(dependency)) {
        codeBlocks.add(
            CodeBlock.of(
                "($T) $L.get($L)", asyncDependencyType(dependency), listArgName, argIndex));
        argIndex++;
      } else {
        codeBlocks.add(
            frameworkTypeUsageStatement(
                CodeBlock.of("$N", fields.get(dependency.key())), dependency.kind()));
      }
    }
    return codeBlocks.build();
  }

  /**
   * Creates a code block for the invocation of the producer method from the module, which should be
   * used entirely within a method body.
   *
   * @param binding The binding to generate the invocation code block for.
   * @param providedTypeName The type name that should be provided by this producer.
   * @param parameterCodeBlocks The code blocks for all the parameters to the producer method.
   */
  private CodeBlock getInvocationCodeBlock(
      ProductionBinding binding,
      TypeName providedTypeName,
      ImmutableList<CodeBlock> parameterCodeBlocks) {
    CodeBlock moduleCodeBlock =
        CodeBlock.of(
            "$L.$L($L)",
            binding.requiresModuleInstance()
                ? "module"
                : CodeBlock.of("$T", ClassName.get(binding.bindingTypeElement().get())),
            binding.bindingElement().get().getSimpleName(),
            makeParametersCodeBlock(parameterCodeBlocks));

    final CodeBlock returnCodeBlock;
    switch (binding.productionKind().get()) {
      case IMMEDIATE:
        returnCodeBlock =
            CodeBlock.of("$T.<$T>immediateFuture($L)", FUTURES, providedTypeName, moduleCodeBlock);
        break;
      case FUTURE:
        returnCodeBlock = moduleCodeBlock;
        break;
      case SET_OF_FUTURE:
        returnCodeBlock = CodeBlock.of("$T.allAsSet($L)", PRODUCERS, moduleCodeBlock);
        break;
      default:
        throw new AssertionError();
    }
    return CodeBlock.of("return $L;", returnCodeBlock);
  }

  /**
   * Converts the list of thrown types into type names.
   *
   * @param thrownTypes the list of thrown types.
   */
  private FluentIterable<? extends TypeName> getThrownTypeNames(
      Iterable<? extends TypeMirror> thrownTypes) {
    return FluentIterable.from(thrownTypes).transform(TypeName::get);
  }
}
