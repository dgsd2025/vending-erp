package top.aole.vend.common.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Collectors;

/**
 * springfox 3.0 与 Spring Boot 2.6+ 的兼容补丁。
 *
 * 背景:SB 2.6 起默认路径匹配策略改为 PathPatternParser,springfox 的
 * WebMvcRequestHandlerProvider 遍历 handlerMappings 时,遇到 patternParser
 * 非空(即没有 AntPath patterns)的 mapping 会 NPE,应用直接起不来。
 *
 * 修法:注册 BeanPostProcessor,把 springfox provider 内部持有的
 * handlerMappings 列表过滤到只剩 patternParser == null 的条目。
 * 配合 application.yml 的 spring.mvc.pathmatch.matching-strategy=ant_path_matcher。
 */
@Configuration
public class SpringFoxCompatConfig {

    @Bean
    public BeanPostProcessor springfoxHandlerProviderBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                String className = bean.getClass().getName();
                if (className.equals("springfox.documentation.spring.web.plugins.WebMvcRequestHandlerProvider")
                        || className.equals("springfox.documentation.spring.web.plugins.WebFluxRequestHandlerProvider")) {
                    filterHandlerMappings(bean);
                }
                return bean;
            }

            @SuppressWarnings("unchecked")
            private void filterHandlerMappings(Object bean) {
                Field field = ReflectionUtils.findField(bean.getClass(), "handlerMappings");
                if (field == null) {
                    return;
                }
                field.setAccessible(true);
                try {
                    List<RequestMappingInfoHandlerMapping> mappings =
                            (List<RequestMappingInfoHandlerMapping>) field.get(bean);
                    List<RequestMappingInfoHandlerMapping> filtered = mappings.stream()
                            .filter(m -> m.getPatternParser() == null)
                            .collect(Collectors.toList());
                    mappings.clear();
                    mappings.addAll(filtered);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("过滤 springfox handlerMappings 失败", e);
                }
            }
        };
    }
}
