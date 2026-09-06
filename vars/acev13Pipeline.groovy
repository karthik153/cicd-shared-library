def call(Map params = [:]) {
    pipeline {
        agent any

        environment {
            // Using the 'params' map passed from the Jenkinsfile
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
                    echo "Building BAR: ${env.BAR_NAME} for App: ${env.APP_NAME}"
                    
                    // FIX: We wrap the ENTIRE command in double quotes so bash treats it as one instruction
                    sh """
                        docker run --rm -u root \
                            -e LICENSE=accept \
                            --entrypoint "" \
                            -v "${WORKSPACE}:/workspace" -w /workspace \
                            ${env.ACE_IMAGE} \
                            /bin/bash -c ". /opt/ibm/ace-13/server/bin/mqsiprofile && mqsicreatebar -data . -b ${env.BAR_NAME} -a ${env.APP_NAME}"
                    """
                }
            }

            stage('Docker Build') {
                steps {
                    echo "Packaging Final Image: ${env.IMAGE_NAME}:${env.TAG}"
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
                echo "FAILURE: Pipeline failed. Check the logs above."
            }
        }
    }
}