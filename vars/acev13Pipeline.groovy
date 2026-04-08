def call(Map config) {
    pipeline {
        agent any
        
        environment {
            APP_NAME = "${config.appName}"
            ACE_PROJECT = "${config.aceProjectName ?: 'test_app'}"
            HOST_PORT = "${config.hostPort ?: '7800'}"
            ADMIN_PORT = "${config.adminPort ?: '7600'}"
            BUILD_CONT = "ace-builder-${env.BUILD_ID}"
            
            // Get Short Git Commit ID (First 7 characters)
            GIT_SHORT_ID = "${env.GIT_COMMIT[0..6]}"
            
            // Define the BAR file name dynamically
            BAR_FILE_NAME = "${config.appName}_${env.GIT_SHORT_ID}.bar"
        }

        stages {
            stage('1. Build ACE BAR') {
                steps {
                    script {
                        sh """
                            # Start ephemeral builder
                            docker run -d --name ${BUILD_CONT} -u root -e LICENSE=accept --entrypoint sleep ace:latest infinity

                            # Copy source to builder
                            docker cp . ${BUILD_CONT}:/workspace

                            # Package BAR with the new dynamic name
                            docker exec -u root ${BUILD_CONT} /bin/bash -c "
                                source /opt/ibm/ace-13/server/bin/mqsiprofile &&
                                mkdir -p /workspace/generated-bars &&
                                ibmint package --input-path /workspace --output-bar-file /workspace/generated-bars/${env.BAR_FILE_NAME} --project ${env.ACE_PROJECT} --compile-maps-and-schemas
                            "

                            # Copy the specific BAR file back to Jenkins workspace
                            mkdir -p generated-bars
                            docker cp ${BUILD_CONT}:/workspace/generated-bars/${env.BAR_FILE_NAME} ./generated-bars/
                            
                            docker stop ${BUILD_CONT}
                            docker rm ${BUILD_CONT}
                        """
                    }
                }
            }

            stage('2. Build App Image') {
                steps {
                    script {
                        def dockerfileContent = """
                            FROM ace:latest
                            USER root
                            
                            # MUST set license at the top so it is available during the build
                            ENV LICENSE=accept
                            
                            # Create work directory
                            RUN . /opt/ibm/ace-13/server/bin/mqsiprofile && \\
                                mqsicreateworkdir /home/aceuser/ace-server
                            
                            # Copy and Deploy the BAR file
                            COPY ./generated-bars/${env.BAR_FILE_NAME} /tmp/${env.BAR_FILE_NAME}
                            
                            RUN . /opt/ibm/ace-13/server/bin/mqsiprofile && \\
                                ibmint deploy --input-bar-file /tmp/${env.BAR_FILE_NAME} --output-work-directory /home/aceuser/ace-server
                            
                            # Set permissions
                            RUN chown -R 1001:0 /home/aceuser/ace-server && \\
                                chmod -R 775 /home/aceuser/ace-server
                            
                            USER 1001
                            
                            # Start server using the work directory
                            CMD ["/opt/ibm/ace-13/server/bin/mqsiserver", "-w", "/home/aceuser/ace-server"]
                            
                            EXPOSE 7800 7600
                        """.stripIndent()

                        writeFile file: 'Dockerfile', text: dockerfileContent
                        sh "docker build -t ${env.APP_NAME}:latest ."
                    }
                }
            }

            stage('3. Deploy Container') {
                steps {
                    sh """
                        docker stop ${env.APP_NAME} || true
                        docker rm ${env.APP_NAME} || true
                        docker run -d \
                            --name ${env.APP_NAME} \
                            -p ${env.HOST_PORT}:7800 \
                            -p ${env.ADMIN_PORT}:7600 \
                            -e LICENSE=accept \
                            ${env.APP_NAME}:latest
                    """
                }
            }
        }
    }
}