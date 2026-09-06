def call(Map pipelineParams) {
    pipeline {
        agent any

        environment {
            APP_NAME = "${pipelineParams.appName}"
            BAR_NAME = "${pipelineParams.barName}"
            IMAGE_NAME = "${pipelineParams.imageName}"
            HOST_PORT = "${pipelineParams.hostPort}"
            TAG = "build-${env.BUILD_NUMBER}"
            ACE_BUILD_IMAGE = "ace_v13:latest"
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
                    // Added -u root to ensure we can write the .bar file to the workspace
                    sh """
                        docker run --rm \
                            -u root \
                            -e BAR_NAME=${env.BAR_NAME} \
                            -e APP_NAME=${env.APP_NAME} \
                            -v "${WORKSPACE}:/workspace" \
                            -w /workspace \
                            ${env.ACE_BUILD_IMAGE} \
                            bash -c ". /opt/ibm/ace-13/server/bin/mqsiprofile && mqsicreatebar -data . -b \\\$BAR_NAME -a \\\$APP_NAME"
                    """
                }
            }

            stage('Docker Build') {
                steps {
                    echo 'Building Docker image...'
                    // FIX 1: You must pass the --build-arg so the Dockerfile knows which BAR to copy
                    sh "docker build --build-arg BAR_FILE=${env.BAR_NAME} -t ${env.IMAGE_NAME}:${env.TAG} ."
                    sh "docker tag ${env.IMAGE_NAME}:${env.TAG} ${env.IMAGE_NAME}:latest"
                }
            }

            stage('Deploy Container') {
                steps {
                    script {
                        echo "Deploying ${env.APP_NAME} to Port ${env.HOST_PORT}"
                        sh "docker rm -f ${env.APP_NAME} || true"
                        
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
                // FIX 2: Use double quotes (") so Jenkins can read the variables. 
                // Single quotes (') print text literally.
                echo "SUCCESS: ${env.APP_NAME} is running on port ${env.HOST_PORT}"
            }
            failure {
                echo "FAILURE: Check the logs for errors."
            }
        }
    }
}