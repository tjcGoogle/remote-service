package com.bestv.remote.configuration;

import com.bestv.remote.annotation.EnableRemoteService;
import com.bestv.remote.annotation.RemoteService;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 扫描 @RemoteService 标记的对象，将描述对象注入Spring
 *
 * @author taojiacheng
 */
public class RemoteServiceRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(@NonNull AnnotationMetadata annotationMetadata, @NonNull BeanDefinitionRegistry registry) {
        /**
         * 查找classpath下所有符合条件的对象
         */
        ClassPathScanningCandidateComponentProvider scanner = getScanner();
        scanner.addIncludeFilter(new AnnotationTypeFilter(RemoteService.class));
        Set<String> basePackages = getBasePackages(annotationMetadata);
        Set<BeanDefinition> candidateComponents = new HashSet<>();
        for (String basePackage : basePackages) {
            candidateComponents.addAll(scanner.findCandidateComponents(basePackage));
        }
        for (BeanDefinition candidateComponent : candidateComponents) {
            if (candidateComponent instanceof AnnotatedBeanDefinition) {
                AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent;
                AnnotationMetadata beanDefinitionMeta = beanDefinition.getMetadata();
                String className = beanDefinitionMeta.getClassName();
                Assert.isTrue(beanDefinitionMeta.isInterface(), "@RemoteService 只能标记 interface");
                Map<String, Object> attributes = beanDefinitionMeta.getAnnotationAttributes(RemoteService.class.getCanonicalName());
                attributes = attributes == null ? new HashMap<>() : attributes;
                registerRemoteService(registry, className, attributes);
            }
        }
    }


    protected Set<String> getBasePackages(AnnotationMetadata importingClassMetadata) {
        Map<String, Object> attributes = importingClassMetadata
                .getAnnotationAttributes(EnableRemoteService.class.getCanonicalName());

        Set<String> basePackages = new HashSet<>();
        for (String pkg : (String[]) attributes.get("basePackages")) {
            if (StringUtils.isNotEmpty(pkg)) {
                basePackages.add(pkg);
            }
        }
        if (basePackages.isEmpty()) {
            basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
        }
        return basePackages;
    }

    protected void registerRemoteService(BeanDefinitionRegistry registry,
                                         String className,
                                         Map<String, Object> attributes) {

        // 获取beanName
        String serviceId = (String) attributes.get("serviceId");
        if (StringUtils.isEmpty(serviceId)) {
            serviceId = className.substring(className.lastIndexOf(".") + 1);
        }

        /**
         * 注册 FactoryBean
         */
        Class<?> resolveClass = ClassUtils.resolveClassName(className, this.getClass().getClassLoader());
        BeanDefinitionBuilder definition = BeanDefinitionBuilder
                .genericBeanDefinition(RemoteServiceFactoryBean.class);
        definition.addConstructorArgValue(resolveClass);

        AbstractBeanDefinition beanDefinition = definition.getBeanDefinition();
        BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, serviceId, new String[]{});
        BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);
    }

    protected ClassPathScanningCandidateComponentProvider getScanner() {
        return new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(@NotNull AnnotatedBeanDefinition beanDefinition) {
                boolean isCandidate = false;
                if (beanDefinition.getMetadata().isIndependent()) {
                    if (!beanDefinition.getMetadata().isAnnotation()) {
                        isCandidate = true;
                    }
                }
                return isCandidate;
            }
        };
    }
}
