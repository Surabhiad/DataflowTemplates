/*
 * Copyright (C) 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.plugin.model;

import static com.google.cloud.teleport.metadata.util.EnumBasedExperimentValueProvider.STREAMING_MODE_ENUM_BASED_EXPERIMENT_VALUE_PROVIDER;
import static com.google.cloud.teleport.metadata.util.MetadataUtils.getParameterNameFromMethod;
import static com.google.common.base.Preconditions.checkArgument;

import com.google.cloud.teleport.metadata.Template;
import com.google.cloud.teleport.metadata.Template.TemplateType;
import com.google.cloud.teleport.metadata.TemplateCategory;
import com.google.cloud.teleport.metadata.TemplateCreationParameter;
import com.google.cloud.teleport.metadata.TemplateCreationParameters;
import com.google.cloud.teleport.metadata.TemplateIgnoreParameter;
import com.google.cloud.teleport.metadata.auto.AutoTemplate;
import com.google.cloud.teleport.metadata.auto.AutoTemplate.ExecutionBlock;
import com.google.cloud.teleport.metadata.util.EnumBasedExperimentValueProvider;
import com.google.cloud.teleport.metadata.util.MetadataUtils;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.beam.sdk.options.Default;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POJO class that wraps the pair of a {@link Class} and the {@link Template} annotation that
 * represent a template.
 */
public class TemplateDefinitions {

  private static final Logger LOG = LoggerFactory.getLogger(TemplateDefinitions.class);

  /** Options that don't need annotations (i.e., from generic parameters). */
  private static final Set<String> IGNORED_FIELDS = Set.of("as");

  /** Default {@link Template} properties. */
  private static final Template DEFAULT_TEMPLATE_ANNOTATION =
      DefaultBatchTemplate.class.getDeclaredAnnotation(Template.class);

  /** Metadata defaultEnvironment key for adding additional default Dataflow Job experiments. */
  static final String ADDITIONAL_EXPERIMENTS = "additionalExperiments";

  /**
   * List of the classes that declare product-specific options. Methods in those classes will not
   * require the usage of @TemplateParameter.
   */
  private static final Set<String> IGNORED_DECLARING_CLASSES = Set.of("Object");

  private Class<?> templateClass;
  private Template templateAnnotation;

  public TemplateDefinitions(Class<?> templateClass, Template templateAnnotation) {
    this.templateClass = templateClass;
    this.templateAnnotation = templateAnnotation;
  }

  public Class<?> getTemplateClass() {
    return templateClass;
  }

  public Template getTemplateAnnotation() {
    return templateAnnotation;
  }

  public boolean isClassic() {

    // Python implies Flex
    if (templateAnnotation.type() == TemplateType.PYTHON
        || templateAnnotation.type() == TemplateType.YAML) {
      return false;
    }

    return templateAnnotation.flexContainerName() == null
        || templateAnnotation.flexContainerName().isEmpty();
  }

  public boolean isFlex() {
    return !isClassic();
  }

  public ImageSpec buildSpecModel(boolean validateFlag) {

    if (validateFlag) {
      validate(templateAnnotation);
    }

    ImageSpec imageSpec = new ImageSpec();

    SdkInfo sdkInfo = new SdkInfo();

    // Xlang templates require the java language.
    if (templateAnnotation.type() == TemplateType.XLANG) {
      sdkInfo.setLanguage("JAVA");
    } else {
      sdkInfo.setLanguage(templateAnnotation.type().toString());
    }

    imageSpec.setSdkInfo(sdkInfo);

    imageSpec = updateStreamingModeRelatedDefaultEnvironment(imageSpec);

    ImageSpecMetadata metadata = new ImageSpecMetadata();
    metadata.setInternalName(templateAnnotation.name());
    metadata.setName(templateAnnotation.displayName());
    metadata.setDescription(
        List.of(templateAnnotation.description()).stream().collect(Collectors.joining("\n\n")));
    metadata.setCategory(
        new ImageSpecCategory(
            templateAnnotation.category().getName(),
            templateAnnotation.category().getDisplayName()));
    metadata.setModule(getClassModule());
    metadata.setDocumentationLink(templateAnnotation.documentation());
    metadata.setGoogleReleased(
        templateAnnotation.documentation() != null
            && templateAnnotation.documentation().contains("cloud.google.com"));
    metadata.setHidden(templateAnnotation.hidden());
    metadata.setPreview(templateAnnotation.preview());
    metadata.setRequirements(Arrays.asList(templateAnnotation.requirements()));

    metadata.setStreaming(templateAnnotation.streaming());
    metadata.setSupportsAtLeastOnce(templateAnnotation.supportsAtLeastOnce());
    metadata.setSupportsExactlyOnce(templateAnnotation.supportsExactlyOnce());

    metadata.setDefaultStreamingMode(templateAnnotation.defaultStreamingMode().toString());

    metadata.setAdditionalDocumentation(
        Arrays.stream(templateAnnotation.additionalDocumentation())
            .map(
                block ->
                    new ImageSpecAdditionalDocumentation(
                        block.name(), Arrays.asList(block.content())))
            .collect(Collectors.toList()));

    if (templateAnnotation.placeholderClass() != null
        && templateAnnotation.placeholderClass() != void.class) {
      metadata.setMainClass(templateAnnotation.placeholderClass().getName());
    } else {
      metadata.setMainClass(templateClass.getName());
    }

    LOG.info(
        "Processing template for class {}. Template name: {}",
        templateClass,
        templateAnnotation.name());

    List<MethodDefinitions> methodDefinitions = new ArrayList<>();

    int order = 0;
    Map<Class<?>, Integer> classOrder = new HashMap<>();

    Class<?> optionsClass = templateAnnotation.optionsClass();
    if (optionsClass == void.class) {
      optionsClass = templateClass;
    }

    if (templateAnnotation.optionsOrder() != null) {
      for (Class<?> options : templateAnnotation.optionsOrder()) {
        classOrder.putIfAbsent(options, order++);
      }
    }

    // If blocks were defined, go through each block's option class
    if (templateAnnotation.blocks()[0] != void.class) {
      try {
        List<ExecutionBlock> executionBlocks = AutoTemplate.buildExecutionBlocks(templateClass);
        for (ExecutionBlock block : executionBlocks) {
          classOrder.putIfAbsent(block.getBlockInstance().getOptionsClass(), order++);
        }
        optionsClass =
            AutoTemplate.createNewOptionsClass(
                executionBlocks,
                templateClass.getClassLoader(),
                AutoTemplate.getDlqInstance(templateClass));
      } catch (Exception e) {
        throw new RuntimeException("Error parsing template blocks", e);
      }
    }

    classOrder.putIfAbsent(optionsClass, order++);

    Set<String> parameterNames = new HashSet<>();

    Method[] methods = optionsClass.getMethods();
    for (Method method : methods) {
      method.setAccessible(true);

      // Ignore the method if it contains @TemplateIgnoreParameter
      if (method.getAnnotation(TemplateIgnoreParameter.class) != null) {
        continue;
      }

      classOrder.putIfAbsent(method.getDeclaringClass(), order++);

      Annotation parameterAnnotation = MetadataUtils.getParameterAnnotation(method);
      if (parameterAnnotation == null) {

        boolean runtime = false;

        TemplateCreationParameters creationParameters =
            method.getAnnotation(TemplateCreationParameters.class);
        String methodName = method.getName();
        if (creationParameters != null) {
          for (TemplateCreationParameter creationParameterCandidate : creationParameters.value()) {

            if (creationParameterCandidate.template().equals(templateAnnotation.name())
                || StringUtils.isEmpty(creationParameterCandidate.template())) {
              runtime = true;

              if (StringUtils.isNotEmpty(creationParameterCandidate.value())) {
                metadata
                    .getRuntimeParameters()
                    .put(
                        getParameterNameFromMethod(methodName), creationParameterCandidate.value());
              }
            }
          }
        }

        TemplateCreationParameter creationParameter =
            method.getAnnotation(TemplateCreationParameter.class);
        if (creationParameter != null) {
          runtime = true;

          if (StringUtils.isNotEmpty(creationParameter.value())) {
            metadata
                .getRuntimeParameters()
                .put(getParameterNameFromMethod(methodName), creationParameter.value());
          }
        }

        // Ignore non-annotated params in this criteria (non-options params)
        if (runtime
            || methodName.startsWith("set")
            || IGNORED_FIELDS.contains(methodName)
            || method.getDeclaringClass().getName().startsWith("org.apache.beam.sdk")
            || method.getDeclaringClass().getName().startsWith("org.apache.beam.runners")
            || method.getReturnType() == void.class
            || IGNORED_DECLARING_CLASSES.contains(method.getDeclaringClass().getSimpleName())) {
          continue;
        }

        if (validateFlag) {
          validate(method);
        }

        continue;
      }

      methodDefinitions.add(new MethodDefinitions(method, parameterAnnotation, classOrder));
    }

    Set<String> skipOptionsSet = Set.of(templateAnnotation.skipOptions());
    Set<String> optionalOptionsSet = Set.of(templateAnnotation.optionalOptions());
    Collections.sort(methodDefinitions);

    for (MethodDefinitions method : methodDefinitions) {
      Annotation parameterAnnotation = method.getTemplateParameter();
      ImageSpecParameter parameter =
          getImageSpecParameter(
              method.getDefiningMethod().getName(),
              method.getDefiningMethod(),
              parameterAnnotation);

      if (skipOptionsSet.contains(parameter.getName())) {
        continue;
      }
      if (optionalOptionsSet.contains(parameter.getName())) {
        parameter.setOptional(true);
      }

      // Set the default value, if any
      Object defaultVal = getDefault(method.getDefiningMethod());
      if (defaultVal != null) {
        parameter.setDefaultValue(String.valueOf(defaultVal));
      }

      if (parameterNames.add(parameter.getName())) {
        metadata.getParameters().add(parameter);
      } else {
        LOG.warn(
            "Parameter {} was already added for the Template {}, skipping repetition.",
            parameter.getName(),
            templateAnnotation.name());
      }
    }

    boolean isFlex = StringUtils.isNotEmpty(templateAnnotation.flexContainerName());
    metadata.setFlexTemplate(isFlex);

    imageSpec.setAdditionalUserLabel(
        "goog-dataflow-provided-template-name", templateAnnotation.name().toLowerCase());
    imageSpec.setAdditionalUserLabel(
        "goog-dataflow-provided-template-type", isFlex ? "flex" : "classic");
    imageSpec.setImage("gcr.io/{project-id}/" + templateAnnotation.flexContainerName());
    imageSpec.setMetadata(metadata);

    metadata.setUdfSupport(
        metadata.getParameters().stream()
            .anyMatch(
                parameter ->
                    parameter.getName().contains("javascriptTextTransformGcsPath")
                        || parameter.getName().contains("javascriptTextTransformFunctionName")));

    return imageSpec;
  }

  /**
   * Updates the defaultEnvironment with additionalExperiments for {@link
   * Template#defaultStreamingMode()} values {@link Template.StreamingMode#EXACTLY_ONCE} or {@link
   * Template.StreamingMode#AT_LEAST_ONCE}. Uses {@link
   * EnumBasedExperimentValueProvider#STREAMING_MODE_ENUM_BASED_EXPERIMENT_VALUE_PROVIDER} to
   * convert {@link Template#defaultStreamingMode()} into the required string representation.
   */
  private ImageSpec updateStreamingModeRelatedDefaultEnvironment(ImageSpec imageSpec) {
    if (!templateAnnotation.streaming()) {
      return imageSpec;
    }
    if (templateAnnotation.defaultStreamingMode().equals(Template.StreamingMode.UNSPECIFIED)) {
      return imageSpec;
    }

    if (imageSpec.getDefaultEnvironment() == null) {
      imageSpec.setDefaultEnvironment(new HashMap<>());
    }

    Map<String, Object> defaultEnvironment = imageSpec.getDefaultEnvironment();

    if (!defaultEnvironment.containsKey(ADDITIONAL_EXPERIMENTS)) {
      defaultEnvironment.put(ADDITIONAL_EXPERIMENTS, new ArrayList<String>());
    }

    // Ok to let a Java cast Exception rather than add a larger amount of code to check for the
    // String element type.
    List<String> additionalExperiments =
        (List<String>) defaultEnvironment.get(ADDITIONAL_EXPERIMENTS);

    // Remove any pre-existing streaming_mode* experiment values.
    Predicate<String> streamingModePrefix =
        STREAMING_MODE_ENUM_BASED_EXPERIMENT_VALUE_PROVIDER.getPrefixAsPattern().asMatchPredicate();
    additionalExperiments.removeIf(streamingModePrefix);

    String streamingModeExperiment =
        STREAMING_MODE_ENUM_BASED_EXPERIMENT_VALUE_PROVIDER.convert(
            templateAnnotation.defaultStreamingMode());

    additionalExperiments.add(streamingModeExperiment);
    defaultEnvironment.put(ADDITIONAL_EXPERIMENTS, additionalExperiments);

    imageSpec.setDefaultEnvironment(defaultEnvironment);

    return imageSpec;
  }

  private String getClassModule() {
    if (templateClass == null) {
      return null;
    }
    URL resource = templateClass.getResource(templateClass.getSimpleName() + ".class");
    if (resource == null) {
      return null;
    }

    Pattern pattern = Pattern.compile(".*/(.*?)/target");
    Matcher matcher = pattern.matcher(resource.getPath());
    if (matcher.find()) {
      return matcher.group(1);
    }
    return null;
  }

  private ImageSpecParameter getImageSpecParameter(
      String originalName, AccessibleObject target, Annotation parameterAnnotation) {
    ImageSpecParameter parameter = new ImageSpecParameter();
    parameter.setName(getParameterNameFromMethod(originalName));
    parameter.processParamType(parameterAnnotation);

    Object defaultValue = getDefault(target);
    String helpText = parameter.getHelpText();
    if (defaultValue != null && !helpText.toLowerCase().contains("default")) {
      if (!helpText.endsWith(".")) {
        helpText += ".";
      }

      if (defaultValue instanceof String && defaultValue.equals("")) {
        helpText += " Defaults to empty.";
      } else {
        helpText += " Defaults to: " + defaultValue + ".";
      }

      parameter.setHelpText(helpText);
    }

    if (!originalName.equalsIgnoreCase("get" + parameter.getName())) {
      LOG.warn(
          "Name for the method and annotation do not match! {} vs {}",
          originalName,
          parameter.getName());
    }
    return parameter;
  }

  private Object getDefault(AccessibleObject definingMethod) {

    if (definingMethod.getAnnotation(Default.String.class) != null) {
      return definingMethod.getAnnotation(Default.String.class).value();
    }
    if (definingMethod.getAnnotation(Default.Boolean.class) != null) {
      return definingMethod.getAnnotation(Default.Boolean.class).value();
    }
    if (definingMethod.getAnnotation(Default.Character.class) != null) {
      return definingMethod.getAnnotation(Default.Character.class).value();
    }
    if (definingMethod.getAnnotation(Default.Byte.class) != null) {
      return definingMethod.getAnnotation(Default.Byte.class).value();
    }
    if (definingMethod.getAnnotation(Default.Short.class) != null) {
      return definingMethod.getAnnotation(Default.Short.class).value();
    }
    if (definingMethod.getAnnotation(Default.Integer.class) != null) {
      return definingMethod.getAnnotation(Default.Integer.class).value();
    }
    if (definingMethod.getAnnotation(Default.Long.class) != null) {
      return definingMethod.getAnnotation(Default.Long.class).value();
    }
    if (definingMethod.getAnnotation(Default.Float.class) != null) {
      return definingMethod.getAnnotation(Default.Float.class).value();
    }
    if (definingMethod.getAnnotation(Default.Double.class) != null) {
      return definingMethod.getAnnotation(Default.Double.class).value();
    }
    if (definingMethod.getAnnotation(Default.Enum.class) != null) {
      return definingMethod.getAnnotation(Default.Enum.class).value();
    }

    return null;
  }

  /** Validates a {@link Template} annotation. */
  private void validate(Template templateAnnotation) {
    getValidator(templateAnnotation.streaming()).accept(templateAnnotation);
  }

  /**
   * Returns the appropriate {@link Consumer} to validate a {@link Template} based on whether it
   * {@param isStreaming}.
   */
  private Consumer<Template> getValidator(boolean isStreaming) {
    if (isStreaming) {
      return templateAnnotation -> {
        if (!templateAnnotation.supportsAtLeastOnce()) {
          checkArgument(
              !templateAnnotation
                  .defaultStreamingMode()
                  .equals(Template.StreamingMode.AT_LEAST_ONCE),
              String.format(
                  "configuration mismatch for supportsAtLeastOnce: '%s': defaultStreamingMode: %s",
                  templateAnnotation.name(), templateAnnotation.defaultStreamingMode()));
        }
        if (!templateAnnotation.supportsExactlyOnce()) {
          checkArgument(
              !templateAnnotation
                  .defaultStreamingMode()
                  .equals(Template.StreamingMode.EXACTLY_ONCE),
              String.format(
                  "configuration mismatch for supportsExactlyOnce: '%s': defaultStreamingMode: %s",
                  templateAnnotation.name(), templateAnnotation.defaultStreamingMode()));
        }
        checkArgument(
            templateAnnotation.supportsAtLeastOnce() || templateAnnotation.supportsExactlyOnce(),
            String.format(
                "template: '%s' streaming == true but neither supportsAtLeastOnce or supportsExactlyOnce",
                templateAnnotation.name()));
      };
    }

    return templateAnnotation -> {
      checkArgument(
          templateAnnotation.defaultStreamingMode().equals(Template.StreamingMode.UNSPECIFIED),
          String.format(
              "template '%s' streaming == false and therefore should not configure a %s other than %s",
              templateAnnotation.name(),
              Template.StreamingMode.class,
              Template.StreamingMode.UNSPECIFIED));

      checkArgument(
          templateAnnotation.supportsAtLeastOnce()
              == DEFAULT_TEMPLATE_ANNOTATION.supportsAtLeastOnce(),
          String.format(
              "template '%s' mismatched configuration: supportsAtLeastOnce: %s != default: %s;"
                  + " only applies to streaming template behavior",
              templateAnnotation.name(),
              templateAnnotation.supportsAtLeastOnce(),
              DEFAULT_TEMPLATE_ANNOTATION.supportsAtLeastOnce()));

      checkArgument(
          templateAnnotation.supportsExactlyOnce()
              == DEFAULT_TEMPLATE_ANNOTATION.supportsExactlyOnce(),
          String.format(
              "template '%s' mismatched configuration: supportsExactlyOnce: %s != default: %s;"
                  + " only applies to streaming template behavior",
              templateAnnotation.name(),
              templateAnnotation.supportsExactlyOnce(),
              DEFAULT_TEMPLATE_ANNOTATION.supportsExactlyOnce()));
    };
  }

  private void validate(Method method) {
    if (method.getAnnotation(Deprecated.class) == null) {
      throw new IllegalArgumentException(
          "Method "
              + method.getDeclaringClass().getName()
              + "."
              + method.getName()
              + "() does not have a @TemplateParameter annotation (and not deprecated).");
    }
  }

  /** Used to validate against default {@link Template} properties. */
  @Template(
      name = "DefaultBatchTemplate",
      displayName = "",
      description = {},
      category = TemplateCategory.BATCH,
      testOnly = true)
  private static class DefaultBatchTemplate {}
}
