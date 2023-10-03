FROM lambci/lambda:build-java11
RUN yum install -y jq
RUN pip install yq
COPY . .
RUN mvn validate && mvn clean && mvn package
CMD mkdir -p /output/ && mv target/$(cat pom.xml | xq -r  '.project| "\(.artifactId)-\(.version).jar"') /output/
