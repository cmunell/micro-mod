# Temporary hack to rebuild the .war file to include the .nar files as .jar files so that Tomcat can find them.
#
# Use this after recompiling micro-mod in the usual fashion (e.g. mvn compile) or after one of the .nar dependencies has changed.

cd target/micro-mod && cp ../classes/edu/cmu/ml/rtw/micro/mod/JSON2015Servlet.class WEB-INF/classes/edu/cmu/ml/rtw/micro/mod/ && cp ~/.m2/repository/edu/cmu/ml/rtw/micro/0.0.1-SNAPSHOT/micro-0.0.1-SNAPSHOT.nar WEB-INF/lib/micro-0.0.1-SNAPSHOT.jar && cp ~/.m2/repository/edu/cmu/ml/rtw/micro-hdp/0.0.1-SNAPSHOT/micro-hdp-0.0.1-SNAPSHOT.nar WEB-INF/lib/micro-hdp-0.0.1-SNAPSHOT.jar && cp ~/.m2/repository/edu/cmu/ml/rtw/micro-event/0.0.1-SNAPSHOT/micro-event-0.0.1-SNAPSHOT.nar WEB-INF/lib/micro-event-0.0.1-SNAPSHOT.jar && cp ~/.m2/repository/edu/cmu/ml/rtw/micro-opinion/0.0.1-SNAPSHOT/micro-opinion-0.0.1-SNAPSHOT.nar WEB-INF/lib/micro-opinion-0.0.1-SNAPSHOT.jar && jar -cvf ../micro-mod.war *

# 2015-02-17: this seems not to be needed: && cp ~/.m2/repository/edu/cmu/ml/rtw/micro-nom/0.0.1-SNAPSHOT/micro-nom-0.0.1-SNAPSHOT.jar WEB-INF/lib/micro-nom-0.0.1-SNAPSHOT.jar && cp ~/.m2/repository/edu/cmu/ml/rtw/micro-regex/0.0.1-SNAPSHOT/micro-regex-0.0.1-SNAPSHOT.jar WEB-INF/lib/micro-regex-0.0.1-SNAPSHOT.jar

