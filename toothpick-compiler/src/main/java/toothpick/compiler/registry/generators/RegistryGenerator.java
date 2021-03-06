package toothpick.compiler.registry.generators;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import javax.lang.model.util.Types;
import toothpick.compiler.common.generators.CodeGenerator;
import toothpick.compiler.registry.targets.RegistryInjectionTarget;
import toothpick.registries.FactoryRegistry;
import toothpick.registries.MemberInjectorRegistry;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a Registry for a given {@link RegistryInjectionTarget}.
 *
 * @see {@link FactoryRegistry} and {@link MemberInjectorRegistry} for Registry types.
 */
public class RegistryGenerator extends CodeGenerator {

  /* @VisibleForTesting */ static int injectionTargetsPerGetterMethod = 200;

  private RegistryInjectionTarget registryInjectionTarget;

  public RegistryGenerator(RegistryInjectionTarget registryInjectionTarget, Types types) {
    super(types);
    this.registryInjectionTarget = registryInjectionTarget;
  }

  @Override
  public String brewJava() {
    TypeSpec.Builder registryTypeSpec = TypeSpec.classBuilder(registryInjectionTarget.registryName)
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .superclass(ClassName.get(registryInjectionTarget.superClass));

    emitConstructor(registryTypeSpec);
    emitGetterMethods(registryTypeSpec);

    JavaFile javaFile = JavaFile.builder(registryInjectionTarget.packageName, registryTypeSpec.build())
        .addFileComment("Generated code from Toothpick. Do not modify!")
        .build();

    return javaFile.toString();
  }

  private void emitConstructor(TypeSpec.Builder registryTypeSpec) {
    MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);

    CodeBlock.Builder iterateChildAddRegistryBlock = CodeBlock.builder();
    for (String childPackageName : registryInjectionTarget.childrenRegistryPackageNameList) {
      ClassName registryClassName = ClassName.get(childPackageName, registryInjectionTarget.registryName);
      iterateChildAddRegistryBlock.addStatement("addChildRegistry(new $L())", registryClassName);
    }

    constructor.addCode(iterateChildAddRegistryBlock.build());
    registryTypeSpec.addMethod(constructor.build());
  }

  private void emitGetterMethods(TypeSpec.Builder registryTypeSpec) {
    TypeVariableName t = TypeVariableName.get("T");
    MethodSpec.Builder getMethod = MethodSpec.methodBuilder(registryInjectionTarget.getterName)
        .addTypeVariable(t)
        .addModifiers(Modifier.PUBLIC)
        .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), t), "clazz")
        .returns(ParameterizedTypeName.get(ClassName.get(registryInjectionTarget.type), t));

    //the ultimate part of the switch is about converting $ to .
    //this is a bad hack, but the easiest workaroung to injectionTarget.getQualifiedName() using only . and not $ for FQN...
    getMethod.addStatement("String className = clazz.getName().replace('$$','.')");
    int numOfBuckets = getNumberOfBuckets(registryInjectionTarget.injectionTargetList);
    getMethod.addStatement("int bucket = (className.hashCode() & $L)", numOfBuckets - 1);
    CodeBlock.Builder switchBlockBuilder = CodeBlock.builder().beginControlFlow("switch(bucket)");

    List<MethodSpec> getterMethodForBucketList = new ArrayList<>(numOfBuckets);
    Map<Integer, List<TypeElement>> getterMethodBuckets = getGetterMethodBuckets(registryInjectionTarget.injectionTargetList);
    for (int i = 0; i < numOfBuckets; i++) {
      List<TypeElement> methodBucket = getterMethodBuckets.get(i);
      if (methodBucket == null) {
        methodBucket = Collections.emptyList();
      }
      MethodSpec getterMethodForBucket = generateGetterMethod(methodBucket, i);
      getterMethodForBucketList.add(getterMethodForBucket);
      switchBlockBuilder.add("case ($L):" + LINE_SEPARATOR, i);
      switchBlockBuilder.addStatement("return $L(clazz, className)", getterMethodForBucket.name);
    }

    switchBlockBuilder.add("default:" + LINE_SEPARATOR);
    switchBlockBuilder.addStatement("return $L(clazz)", registryInjectionTarget.childrenGetterName);
    switchBlockBuilder.endControlFlow();
    getMethod.addCode(switchBlockBuilder.build());
    registryTypeSpec.addMethod(getMethod.build());
    registryTypeSpec.addMethods(getterMethodForBucketList);
  }

  private MethodSpec generateGetterMethod(List<TypeElement> getterMethodBucket, int index) {
    TypeVariableName t = TypeVariableName.get("T");
    MethodSpec.Builder getMethod = MethodSpec.methodBuilder(registryInjectionTarget.getterName + "Bucket" + index)
        .addTypeVariable(t)
        .addModifiers(Modifier.PRIVATE)
        .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), t), "clazz")
        .addParameter(String.class, "className")
        .returns(ParameterizedTypeName.get(ClassName.get(registryInjectionTarget.type), t));

    CodeBlock.Builder switchBlockBuilder = CodeBlock.builder().beginControlFlow("switch(className)");
    String typeSimpleName = registryInjectionTarget.type.getSimpleName();

    for (TypeElement injectionTarget : getterMethodBucket) {
      switchBlockBuilder.add("case ($S):" + LINE_SEPARATOR, injectionTarget.getQualifiedName().toString());
      switchBlockBuilder.addStatement("return ($L<T>) new $L$$$$$L()", typeSimpleName, getGeneratedFQNClassName(injectionTarget), typeSimpleName);
    }

    switchBlockBuilder.add("default:" + LINE_SEPARATOR);
    switchBlockBuilder.addStatement("return $L(clazz)", registryInjectionTarget.childrenGetterName);
    switchBlockBuilder.endControlFlow();
    getMethod.addCode(switchBlockBuilder.build());
    return getMethod.build();
  }

  private Map<Integer, List<TypeElement>> getGetterMethodBuckets(List<TypeElement> injectionTargetList) {
    int numOfBuckets = getNumberOfBuckets(injectionTargetList);
    Map<Integer, List<TypeElement>> getterMethodBuckets = new HashMap<>();

    for (TypeElement injectionTarget : injectionTargetList) {
      int index = injectionTarget.getQualifiedName().toString().hashCode() & (numOfBuckets - 1);
      List<TypeElement> methodBucket = getterMethodBuckets.get(index);
      if (methodBucket == null) {
        methodBucket = new ArrayList<>();
        getterMethodBuckets.put(index, methodBucket);
      }
      methodBucket.add(injectionTarget);
    }

    return getterMethodBuckets;
  }

  private int getNumberOfBuckets(List<TypeElement> injectionTargetList) {
    int minNumOfBuckets = (injectionTargetList.size() + injectionTargetsPerGetterMethod - 1) / injectionTargetsPerGetterMethod;
    return roundUpToPowerOfTwo(minNumOfBuckets);
  }

  private int roundUpToPowerOfTwo(int i) {
    i--;
    i |= i >>>  1;
    i |= i >>>  2;
    i |= i >>>  4;
    i |= i >>>  8;
    i |= i >>> 16;
    return i + 1;
  }

  @Override
  public String getFqcn() {
    return registryInjectionTarget.getFqcn();
  }
}
