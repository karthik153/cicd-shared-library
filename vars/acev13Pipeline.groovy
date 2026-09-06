def call(Map pipelineParams) {
    pipeline {
        agent any

        environment {
            // Mapping these EXACTLY to your Jenkinsfile keys
            APP_NAME    = "${pipelineParams.appName}"
            BAR_NAME    = "${pipelineParams.barName}"
            IMAGE_NAME  = "${pipelineParams.imageName}"
            HOST_PORT   = "${pipelineParams.hostPort}" 
            
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
                    echo "Building BAR: ${env.BAR_NAME} for App: ${env.APP_NAME}"
                    // Using -e LICENSE=accept so the mqsicreatebar tool can run
                    sh """
                        docker run --rm -u root \
                            -e LICENSE=accept \
                            -v "${WORKSPACE}:/workspace" -w /workspace \
                            ${env.ACE_IMAGE} \
                            bash -c ". /opt/ibm/ace-13/server/bin/mqsiprofile && mqsicreatebar -data . -b ${env.BAR_NAME} -a ${env.APP_NAME}"
                    """
                }
            }

            stage('Docker Build') {
                steps {
                    echo "Packaging Final Image: ${env.IMAGE_NAME}:${env.TAG}"
                    // Passing the BAR_NAME as a build-arg to your Dockerfile
                    sh "docker build --build-arg BAR_FILE=${env.BAR_NAME} -t ${env.IMAGE_NAME}:${env.TAG} ."
                    sh "docker tag ${env.IMAGE_NAME}:${env.TAG} ${env.IMAGE_NAME}:latest"
                }
            }

            stage('Deploy Container') {
                steps {
                    script {
                        echo "Deploying to Port: ${env.HOST_PORT}"
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

        post {
            success {
                echo "SUCCESS: ${env.APP_NAME} is live at http://localhost:${env.HOST_PORT}"
            }
            failure {
                echo "FAILURE: Check the Jenkins console logs for errors."
            }
        }
    }
}