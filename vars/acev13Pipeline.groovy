def call(Map config) {
    pipeline {
        agent any
        
        environment {
            APP_NAME = "${config.appName}"
            // Use config value, default to 'test_app' if missing
            ACE_PROJECT = "${config.aceProjectName ?: 'test_app'}"
            HOST_PORT = "${config.hostPort ?: '7800'}"
            ADMIN_PORT = "${config.adminPort ?: '9483'}"
            BUILD_CONT = "ace-builder-${env.BUILD_ID}"
        }

        stages {
            // Inside vars/acev13Pipeline.groovy in your shared library repo

			stage('1. Build ACE BAR') {
				steps {
					script {
						sh """
							# 1. Start the container with the LICENSE accepted
							docker run -d --name ${BUILD_CONT} -u root -e LICENSE=accept --entrypoint sleep ace:latest infinity

							# 2. Copy code in
							docker cp . ${BUILD_CONT}:/workspace

							# 3. Run packaging
							docker exec -u root ${BUILD_CONT} /bin/bash -c "
								source /opt/ibm/ace-13/server/bin/mqsiprofile &&
								mkdir -p /workspace/generated-bars &&
								ibmint package --input-path /workspace --output-bar-file /workspace/generated-bars/app.bar --project ${env.ACE_PROJECT} --compile-maps-and-schemas
							"

							# 4. Copy BAR out
							mkdir -p generated-bars
							docker cp ${BUILD_CONT}:/workspace/generated-bars/app.bar ./generated-bars/app.bar

							# 5. Cleanup
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
                            RUN mkdir -p /home/aceuser/initial-config/bars
                            COPY ./generated-bars/app.bar /home/aceuser/initial-config/bars/
                            RUN chown -R 1001:0 /home/aceuser/initial-config/bars && \
                                chmod -R 775 /home/aceuser/initial-config/bars
                            USER 1001
                            ENV LICENSE=accept
                            EXPOSE 7800 9483
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
                            -p ${env.ADMIN_PORT}:9483 \
                            -e LICENSE=accept \
                            ${env.APP_NAME}:latest
                    """
                }
            }
        }
    }
}