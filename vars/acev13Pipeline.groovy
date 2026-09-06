def call(Map params = [:]) {
    pipeline {
        agent any

        environment {
            APP_NAME      = "${params.appName ?: 'test-app'}"
            BAR_NAME      = "${params.barName ?: 'test.bar'}"
            IMAGE_NAME    = "${params.imageName ?: 'ace-app'}"
            HOST_PORT     = "${params.hostPort ?: '7800'}"
            
            // Use 'host.docker.internal' so the Jenkins container can see the Nexus container on your Windows host
            REGISTRY_URL  = "host.docker.internal:5000" 
            FULL_IMAGE    = "${env.REGISTRY_URL}/${env.IMAGE_NAME}"
            
            TAG           = "v${env.BUILD_NUMBER}"
            ACE_IMAGE     = "ace_v13:latest"
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
                        echo "Building BAR using Builder Container..."
                        sh """
                            set -e
                            BUILDER_CONTAINER="ace-bar-builder-${env.BUILD_NUMBER}"
                            trap 'docker rm -f "\$BUILDER_CONTAINER" >/dev/null 2>&1 || true' EXIT
                            
                            docker create --name "\$BUILDER_CONTAINER" -u root -e LICENSE=accept --entrypoint "/bin/bash" ${env.ACE_IMAGE} -c "while true; do sleep 3600; done"
                            docker start "\$BUILDER_CONTAINER"
                            docker exec "\$BUILDER_CONTAINER" mkdir -p /workspace/src
                            docker cp . "\$BUILDER_CONTAINER:/workspace/src/"
                            docker exec -w /workspace "\$BUILDER_CONTAINER" /bin/bash -lc ". /opt/ibm/ace-13/server/bin/mqsiprofile && ibmint package --input-path ./src --output-bar-file ${env.BAR_NAME} --project ${env.APP_NAME}"
                            docker cp "\$BUILDER_CONTAINER:/workspace/${env.BAR_NAME}" "${env.BAR_NAME}"
                        """
                    }
                }
            }

            stage('Docker Build & Push') {
                steps {
                    // We use withCredentials to pull the username/password from Jenkins safely
                    withCredentials([usernamePassword(credentialsId: 'nexus-creds', passwordVariable: 'NEXUS_PWD', usernameVariable: 'NEXUS_USER')]) {
                        script {
                            echo "Packaging and Pushing to Nexus..."
                            
                            def dockerfileContent = libraryResource 'Dockerfile.ace-generic'
                            writeFile file: 'Dockerfile', text: dockerfileContent

                            sh "docker build --build-arg BAR_FILE=${env.BAR_NAME} -t ${env.FULL_IMAGE}:${env.TAG} ."
                            sh "docker tag ${env.FULL_IMAGE}:${env.TAG} ${env.FULL_IMAGE}:latest"

                            // Manual login and push via shell
                            sh "echo \$NEXUS_PWD | docker login ${env.REGISTRY_URL} -u \$NEXUS_USER --password-stdin"
                            sh "docker push ${env.FULL_IMAGE}:${env.TAG}"
                            sh "docker push ${env.FULL_IMAGE}:latest"
                            sh "docker logout ${env.REGISTRY_URL}"
                        }
                    }
                }
            }

            stage('Deploy (CD)') {
                steps {
                    script {
                        echo "Deploying from Nexus to Port: ${env.HOST_PORT}"
                        sh "docker rm -f ${env.APP_NAME} || true"
                        // Pull from Nexus first to ensure we have the right version
                        sh "docker pull ${env.FULL_IMAGE}:${env.TAG}"
                        sh """
                            docker run -d --name ${env.APP_NAME} \
                            -p ${env.HOST_PORT}:7800 -p 7600:7600 \
                            -e LICENSE=accept \
                            ${env.FULL_IMAGE}:${env.TAG}
                        """
                    }
                }
            }
        }
    }
}