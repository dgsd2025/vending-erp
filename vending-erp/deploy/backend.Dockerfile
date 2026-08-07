# 售卖机 ERP 后端生产镜像(ole 规范:多阶段 + eclipse-temurin:8-jre)
# 编译期用 JDK17(Spring Boot 2.7 需要),字节码 target=8(pom maven.compiler.release=8),运行期 8-jre
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 阿里云 maven 镜像加速(settings.xml 已在仓库内)
COPY backend/settings.xml /root/.m2/settings.xml
COPY backend/pom.xml .
RUN mvn -s /root/.m2/settings.xml -q dependency:go-offline
COPY backend/src ./src
RUN mvn -s /root/.m2/settings.xml -q clean package -DskipTests

FROM eclipse-temurin:8-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8081
# 生产配置全部走环境变量(.env 注入),不写死
ENTRYPOINT ["java","-Dfile.encoding=UTF-8","-jar","app.jar"]
