def call(Map pipelineParams = [:]) {
    pipeline {
        agent any

        environment {
            // Using a very explicit way to extract parameters from the Jenkinsfile call
            APP_NAME    = "${pipelineParams.appName ?: 'test-app'}"
            BAR_NAME    = "${pipelineParams.barName ?: 'test.bar'}"
            IMAGE_NAME  = "${pipelineParams.imageName ?: 'ace-app'}"
            HOST_PORT   = "${pipelineParams.hostPort ?: '7800'}"
            
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
                        
                        // We use escaped single quotes (\') to ensure the ENTIRE command 
                        // string reaches the container's bash -c
                        sh """
                            docker run --rm -u root \
                                -e LICENSE=accept \
                                --entrypoint "" \
                                -v "${WORKSPACE}:/workspace" -w /workspace \
                                ${env.ACE_IMAGE} \
                                /bin/bash -c '. /opt/ibm/ace-13/server/bin/mqsiprofile && mqsicreatebar -data . -b ${env.BAR_NAME} -a ${env.APP_NAME}'
                        """
                    }
                }
            }

            stage('Docker Build') {
                steps {
                    echo "Packaging Image: ${env.IMAGE_NAME}:${env.TAG}"
                    sh "docker build --build-arg BAR_FILE=${env.BAR_NAME} -t ${env.IMAGE_NAME}:${env.TAG} ."
                    sh "docker tag ${env.IMAGE_NAME}:${env.TAG} ${env.IMAGE_NAME}:latest"
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