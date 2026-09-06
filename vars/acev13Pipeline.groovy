def call(Map params = [:]) {
    pipeline {
        agent any

        environment {
            // Use 'params' directly from the function argument
            APP_NAME    = "${params.appName ?: 'test-app'}"
            BAR_NAME    = "${params.barName ?: 'test.bar'}"
            IMAGE_NAME  = "${params.imageName ?: 'ace-app'}"
            HOST_PORT   = "${params.hostPort ?: '7800'}"
            
            TAG         = "build-${env.BUILD_NUMBER}"
            ACE_IMAGE   = "ace_v13:latest"
        }

        stages {
            stage('Clean & Checkout') {
                steps {
                    cleanWs()
                    checkout scm
                }
            }

            stage('Build ACE BAR') {
                steps {
                    script {
                        echo "Building BAR: ${env.BAR_NAME} for App: ${env.APP_NAME}"
                        
                        sh """
                            set -e
                            BUILDER_CONTAINER="ace-bar-builder-${env.BUILD_NUMBER}"
                            trap 'docker rm -f "\$BUILDER_CONTAINER" >/dev/null 2>&1 || true' EXIT
                            docker create --name "\$BUILDER_CONTAINER" -u root \
                                -e LICENSE=accept \
                                --entrypoint "/bin/bash" \
                                ${env.ACE_IMAGE} \
                                -c "while true; do sleep 3600; done" >/dev/null
                            docker start "\$BUILDER_CONTAINER" >/dev/null
                            docker exec "\$BUILDER_CONTAINER" mkdir -p /workspace
                            docker cp "${env.APP_NAME}" "\$BUILDER_CONTAINER:/workspace/"
                            docker exec -w /workspace "\$BUILDER_CONTAINER" /bin/bash -lc "source /opt/ibm/ace-13/server/bin/mqsiprofile && ibmint package --input-path './${env.APP_NAME}' --output-bar-file '${env.BAR_NAME}' --project '${env.APP_NAME}'"
                            docker cp "\$BUILDER_CONTAINER:/workspace/${env.BAR_NAME}" "${env.BAR_NAME}"
                        """
                    }
                }
            }

            stage('Docker Build') {
                steps {
                    script {
                        echo "Packaging Image: ${env.IMAGE_NAME}:${env.TAG}"
                        writeFile file: 'Dockerfile.ace-generic', text: libraryResource('Dockerfile.ace-generic')
                        sh """
                            mkdir -p generated-bars
                            cp '${env.BAR_NAME}' 'generated-bars/${env.BAR_NAME}'
                            docker build -f Dockerfile.ace-generic --build-arg BAR_FILE='${env.BAR_NAME}' -t ${env.IMAGE_NAME}:${env.TAG} .
                        """
                        sh "docker tag ${env.IMAGE_NAME}:${env.TAG} ${env.IMAGE_NAME}:latest"
                    }
                }
            }

            stage('Deploy Container') {
                steps {
                    script {
                        sh "docker rm -f ${env.APP_NAME} || true"
                        sh """
                            docker run -d --name ${env.APP_NAME} \
                            -p ${env.HOST_PORT}:7800 -p 7600:7600 \
                            -e LICENSE=accept \
                            ${env.IMAGE_NAME}:${env.TAG}
                        """
                    }
                }
            }
        }
    }
}