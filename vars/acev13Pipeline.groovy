def call(Map pipelineParams) {
    pipeline {
        agent any

        environment {
            // Define environment variables here
            APP_NAME = "${pipelineParams.appName}"
            BAR_NAME = "${pipelineParams.barName}"
            IMAGE_NAME = "${pipelineParams.imageName}"
            HOST_PORT = "${pipelineParams.hostPort}"

            TAG         = "build-${env.BUILD_NUMBER}"
            PROFILE_PATH = "/opt/ibm/ace-13/server/bin/mqsiprofile"
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
                    echo 'Building ACE BAR...'
                    sh """
                        . ${PROFILE_PATH}
                        mqsicreatebar -data . -b ${BAR_NAME} -a ${APP_NAME}
                    """
                }
            }
            stage('Docker Build') {
                steps {
                    echo 'Building Docker image...'
                    sh """
                        docker build -t ${IMAGE_NAME}:${TAG} .
                    """
                    sh """
                        docker tag ${IMAGE_NAME}:${TAG} ${IMAGE_NAME}:latest
                    """
                }
            }
            stage('Deploy Container') {
                steps {
                    echo "Deploying to Port ${env.HOST_PORT}"
                        // Idempotency: Remove old container if it exists
                        sh "docker rm -f ${env.APP_NAME} || true"
                        
                        // Run using the parameters passed from Jenkinsfile
                        sh """
                            docker run -d \
                            --name ${env.APP_NAME} \
                            -p ${env.HOST_PORT}:7800 \
                            -p 7600:7600 \
                            -e LICENSE=accept \
                            ${env.IMAGE_NAME}:${env.TAG}
                        """
                    }
                }
            }
        }

        post {
            success {
                echo 'SUCCESS: ${env.APP_NAME} is running on port ${env.HOST_PORT}'
            }
            failure {
                echo 'FAILURE: Check the logs for errors.'
            }
        }
    }