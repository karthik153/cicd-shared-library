def call(Map config) {
    pipeline {
        agent any
        
        environment {
            APP_NAME = "${config.appName}"
            // Use the name passed from Jenkinsfile, or default to 'testapp'
            ACE_PROJECT = "${config.aceProjectName ?: 'testapp'}"
            HOST_PORT = "${config.hostPort ?: '7800'}"
            ADMIN_PORT = "${config.adminPort ?: '9483'}"
        }

        stages {
            stage('1. Build ACE BAR') {
                steps {
                    sh """
                        docker run --rm \
                            -u root \
                            --entrypoint "" \
                            -e LICENSE=accept \
                            -v ${WORKSPACE}:/src \
                            ace:latest /bin/bash -c "
                                source /opt/ibm/ace-13/server/bin/mqsiprofile && \
                                echo 'Checking workspace content:' && ls -F /src && \
                                mkdir -p /src/generated-bars && \
                                ibmint package --input-path /src --output-bar-file /src/generated-bars/app.bar --project ${env.ACE_PROJECT} --compile-maps-and-schemas && \
                                chmod -R 777 /src/generated-bars
                            "
                    """
                }
            }

            stage('2. Build App Image') {
                steps {
                    script {
                        def dockerfileContent = """
                            FROM ace:latest
                            USER root
                            RUN mkdir -p /home/aceuser/initial-config/bars
                            COPY ./generated-bars/app.bar /home/aceuser/initial-config/bars/
                            RUN chown -R 1001:0 /home/aceuser/initial-config/bars && \
                                chmod -R 775 /home/aceuser/initial-config/bars
                            USER 1001
                            ENV LICENSE=accept
                            EXPOSE 7800 9483
                        """.stripIndent()

                        writeFile file: 'Dockerfile', text: dockerfileContent
                        sh "docker build -t ${env.APP_NAME}:latest ."
                    }
                }
            }

            stage('3. Deploy Container') {
                steps {
                    sh """
                        docker stop ${env.APP_NAME} || true
                        docker rm ${env.APP_NAME} || true
                        docker run -d \
                            --name ${env.APP_NAME} \
                            -p ${env.HOST_PORT}:7800 \
                            -p ${env.ADMIN_PORT}:9483 \
                            -e LICENSE=accept \
                            ${env.APP_NAME}:latest
                    """
                }
            }
        }
    }
}