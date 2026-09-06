def call(Map config) {
    pipeline {
        agent any
        
        environment {
            APP_NAME = "${config.appName}"
            ACE_PROJECT = "${config.aceProjectName ?: 'test_app'}"
            ACE_ENVIRONMENT = "${config.environment ?: 'dev'}"
            BUILD_CONT = "ace-builder-${env.BUILD_ID}"

            // Get Short Git Commit ID (First 7 characters) with fallback for local/non-Git runs
            GIT_SHORT_ID = "${(env.GIT_COMMIT ?: 'dev0000')[0..6]}"

            // Define the BAR file name dynamically with version info
            BAR_FILE_NAME = "${config.appName}_${env.GIT_SHORT_ID}.bar"

            // Unique container name per BAR/build for independent integration servers
            CONTAINER_NAME = "ace-${config.appName}-${env.BUILD_NUMBER}"
        }

        stages {
            stage('1. Build ACE BAR') {
                steps {
                    script {
                        sh """
                            # Start ephemeral builder
                            docker run -d --name ${BUILD_CONT} -u root -e LICENSE=accept --entrypoint sleep ace_v13:latest infinity

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
                            FROM ace_v13:latest
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

                            CMD ["/bin/bash", "-c", ". /opt/ibm/ace-13/server/bin/mqsiprofile && exec /opt/ibm/ace-13/server/bin/mqsiserver -w /home/aceuser/ace-server"]

                            EXPOSE 7800 7600
                        """.stripIndent()

                        writeFile file: 'Dockerfile', text: dockerfileContent
                        sh "docker build -t ${env.APP_NAME}:latest ."
                    }
                }
            }

            stage('3. Deploy Container') {
                steps {
                    script {
                        // Load port registry manager and allocate ports
                        def portRegistry = load 'vars/portRegistry.groovy'
                        def ports = portRegistry.allocatePort(env.APP_NAME, env.ACE_ENVIRONMENT, env.BUILD_NUMBER)
                        env.CONTAINER_HOST_PORT = ports.hostFlowPort.toString()
                        env.CONTAINER_ADMIN_PORT = ports.hostAdminPort.toString()
                        env.PORT_KEY = ports.key
                        
                        sh """
                            echo "Deploying ACE container: ${env.CONTAINER_NAME}"
                            echo "Environment: ${env.ACE_ENVIRONMENT}"
                            echo "Flow port: localhost:${env.CONTAINER_HOST_PORT} -> 7800"
                            echo "Admin port: localhost:${env.CONTAINER_ADMIN_PORT} -> 7600"
                            echo "BAR file: ${env.BAR_FILE_NAME}"

                            docker rm -f ${env.CONTAINER_NAME} || true

                            docker run -d \
                                --name ${env.CONTAINER_NAME} \
                                -p ${env.CONTAINER_HOST_PORT}:7800 \
                                -p ${env.CONTAINER_ADMIN_PORT}:7600 \
                                -e LICENSE=accept \
                                -l app=${env.APP_NAME} \
                                -l build=${env.BUILD_NUMBER} \
                                -l bar_file=${env.BAR_FILE_NAME} \
                                -l git_commit=${env.GIT_SHORT_ID} \
                                -l environment=${env.ACE_ENVIRONMENT} \
                                -l port_key=${env.PORT_KEY} \
                                ${env.APP_NAME}:latest

                            sleep 2
                            docker ps --filter "name=${env.CONTAINER_NAME}" | grep ${env.CONTAINER_NAME} || exit 1
                            echo "✓ Container ${env.CONTAINER_NAME} is running successfully"
                        """
                    }
                }
            }
        }
    }
}