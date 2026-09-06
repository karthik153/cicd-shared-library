def call(Map params = [:]) {
    pipeline {
        agent any

        environment {
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
                        echo "Building BAR: ${env.BAR_NAME} using ibmint..."
                        
                        sh """
                            set -e
                            BUILDER_CONTAINER="ace-bar-builder-${env.BUILD_NUMBER}"
                            
                            # Ensure cleanup happens even if build fails
                            trap 'docker rm -f "\$BUILDER_CONTAINER" >/dev/null 2>&1 || true' EXIT
                            
                            # 1. Create a persistent builder container
                            docker create --name "\$BUILDER_CONTAINER" -u root \
                                -e LICENSE=accept \
                                --entrypoint "/bin/bash" \
                                ${env.ACE_IMAGE} \
                                -c "while true; do sleep 3600; done" >/dev/null
                            
                            docker start "\$BUILDER_CONTAINER" >/dev/null
                            
                            # 2. Copy the ENTIRE workspace into the container
                            docker exec "\$BUILDER_CONTAINER" mkdir -p /workspace/src
                            docker cp . "\$BUILDER_CONTAINER:/workspace/src/"
                            
                            # 3. Run the build using ibmint
                            # We point --input-path to the src folder we just copied
                            docker exec -w /workspace "\$BUILDER_CONTAINER" /bin/bash -lc "
                                . /opt/ibm/ace-13/server/bin/mqsiprofile && \
                                ibmint package --input-path ./src --output-bar-file ${env.BAR_NAME} --project ${env.APP_NAME}
                            "
                            
                            # 4. Copy the finished BAR back to Jenkins host
                            docker cp "\$BUILDER_CONTAINER:/workspace/${env.BAR_NAME}" "${env.BAR_NAME}"
                        """
                    }
                }
            }

            stage('Docker Build') {
                steps {
                    script {
                        echo "Packaging Image: ${env.IMAGE_NAME}:${env.TAG}"
                        
                        // Pull the Dockerfile from the Shared Library 'resources' folder
                        def dockerfileContent = libraryResource 'Dockerfile.ace-generic'
                        writeFile file: 'Dockerfile', text: dockerfileContent
                        
                        // Build using the BAR we just extracted from the builder container
                        sh """
                            docker build -f Dockerfile --build-arg BAR_FILE='${env.BAR_NAME}' -t ${env.IMAGE_NAME}:${env.TAG} .
                        """
                        sh "docker tag ${env.IMAGE_NAME}:${env.TAG} ${env.IMAGE_NAME}:latest"
                    }
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
            always {
                // Final safety cleanup of the workspace BAR file
                echo "Cleaning up build artifacts..."
            }
        }
    }
}