Personal Data annotation processor for Java
================================================================

This repo has the sources for the annotation processors described in
"Annotation-Based Static Analysis for Personal Data Protection" and
"Extracting Layered Privacy Language Purposes from Web Services".

Usage
-----------------

### Build processor

```
mvn clean install
```

### Add dependency

    <dependency>
        <groupId>fi.geniem.gdpr</groupId>
        <artifactId>personaldataflow</artifactId>
        <version>--INSERT BUILT VERSION--</version>
    </dependency>


### Add annotation processor

There are two processors in this repo: privacy policy data extractor and personal data usage validator.
Choose one or both.

    <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
            <source>1.8</source>
            <target>1.8</target>
            <annotationProcessors>
                <annotationProcessor>
                    fi.geniem.gdpr.personaldataflow.PersonalDataMetricsProcessor
                </annotationProcessor>
            </annotationProcessors>
        </configuration>
    </plugin>


### Build your code (for example:)

`mvn clean install`

### Create visualization from privacy policy data:

`python3 visualization/generate.py <folder location>`

