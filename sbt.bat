java -XX:MaxPermSize=128m -XX:+UseConcMarkSweepGC -XX:+CMSPermGenSweepingEnabled -XX:+CMSClassUnloadingEnabled -Dsbt.boot.directory="./.sbt-boot" -Dsbt.global.home="./.sbt" -Dsbt.home="./.sbt" -Dsbt.ivy.home=%IVY_HOME%\.ivy2\ -Dsbt.global.staging="./.sbt-staging" -jar sbt-launch.jar 
