group = "dev.redicloud.repository.server"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":utils"))
    compileOnly(project(":events"))
    compileOnly(project(":repositories:player-repository"))
    compileOnly(project(":repositories:service-repository"))
    compileOnly(project(":repositories:configuration-template-repository"))
    compileOnly(project(":database"))
    compileOnly(project(":packets"))
}