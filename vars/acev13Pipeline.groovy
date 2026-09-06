def call(Map pipelineParams) {
    pipeline {
        agent any

        environment {
            APP_NAME    = "${pipelineParams.appName}"
            BAR_NAME    = "${pipelineParams.barName}"
            IMAGE_NAME  = "${pipelineParams.imageName}"
            // Changed from hostPort to port to match your Jenkinsfile
            HOST_PORT   = "${pipelineParams.port}" 
            TAG         = "build-${env.BUILD_NUMBER}"
            ACE_BUILD_IMAGE = "ace_v13:latest"
        }

        stages {
            // ... (keep Clean & Checkout stage as is)

            stage('Build ACE BAR') {
                steps {
                    echo "Building BAR: ${env.BAR_NAME} for App: ${env.APP_NAME}"
                    sh """
                        docker run --rm -u root \
                            -v "${WORKSPACE}:/workspace" -w /workspace \
                            ${env.ACE_BUILD_IMAGE} \
                            bash -c ". /opt/ibm/ace-13/server/bin/mqsiprofile && mqsicreatebar -data . -b ${env.BAR_NAME} -a ${env.APP_NAME}"
                    """
                }
            }
            
            // ... (keep Docker Build and Deploy stages as is)
        }

        post {
            success {
                // IMPORTANT: Changed to double quotes (") for variable expansion
                echo "SUCCESS: ${env.APP_NAME} is running on port ${env.HOST_PORT}"
            }
            failure {
                echo "FAILURE: Check the logs for errors."
            }
        }
    }
}