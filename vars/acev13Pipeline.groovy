def call(Map config) {
    pipeline {
        agent any
        
        environment {
            // Configuration passed from the app
            APP_NAME = "${config.appName}"
            ACE_PROJECT = "${config.aceProjectName}"
            HOST_PORT = "${config.hostPort ?: '7800'}"
            ADMIN_PORT = "${config.adminPort ?: '9483'}"
        }

        stages {
            stage('1. Build ACE BAR') {
                steps {
                    // Hidden BAR build logic using your local v13 image
                    sh """
                        docker run --rm -v ${WORKSPACE}:/src ace:latest /bin/bash -c "
                            source /opt/ibm/ace-13/server/bin/mqsiprofile && \
                            mkdir -p /src/generated-bars && \
                            ibmint package --input-path /src --output-bar-file /src/generated-bars/app.bar --project ${env.ACE_PROJECT}
                        "
                    """
                }
            }

            stage('2. Build App Image') {
                steps {
                    script {
                        // Uses the library's existing docker function to build
                        dockerLib.buildImage("${env.APP_NAME}")
                    }
                }
            }

            stage('3. Deploy Container') {
                steps {
                    // Hidden deployment logic
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