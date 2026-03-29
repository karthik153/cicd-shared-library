pipeline {
    agent any
    parameters {
        string(name: 'APP_GIT_URL', defaultValue: '', description: 'GitHub URL of the ACE Application')
        string(name: 'APP_BRANCH', defaultValue: 'main', description: 'Branch to checkout')
    }
    stages {
        stage('1. Checkout ACE App Repo') {
            steps {
                // Instead of nuking the whole workspace, just enter the 'app-src' folder and wipe only that folder!
                dir('app-src') {
                    deleteDir() 
                    git url: "${params.APP_GIT_URL}", branch: "${params.APP_BRANCH}", credentialsId: 'github-creds'
                }
            }
        }
        stage('2. Read App Configuration') {
            steps {
                script {
                    def config = readJSON file: "app-src/pipeline-config.json"
                    env.APP_NAME = config.appName
                    // Fix: Force lowercase exclusively for Docker naming rules!
                    env.IMAGE_NAME = config.appName.toLowerCase() 
                    
                    env.REQUIRES_MQ = config.dependencies.mq
                    env.REQUIRES_REDIS = config.dependencies.redis
                    env.REQUIRES_CREDS = config.requiresDbParms
                }
            }
        }
        stage('3. Configure MQ/DB Passwords') {
            when { expression { env.REQUIRES_CREDS == 'true' } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'DB_CRED', passwordVariable: 'DB_PWD', usernameVariable: 'DB_USER')]) {
                    script {
                        sh "echo 'odbc::myDB ${DB_USER} ${DB_PWD}' > setdbparms.txt"
                        sh "echo 'redis::myRedis serverName::${DB_PWD}' >> setdbparms.txt" 
                    }
                }
            }
        }
        stage('4. Build Target Container') {
            steps {
                script {
                    // Fix: Passes standard Case-Sensitive APP_NAME to mqsicreatebar, 
                    // but uses lowercase IMAGE_NAME for tagging the Docker container.
                    sh "docker build -f Dockerfile.ace-generic --build-arg APP_NAME=${env.APP_NAME} -t app-ace-${env.IMAGE_NAME}:latest ."
                }
            }
        }
        stage('5. Run Standardized ACE Container') {
            steps {
                script {
                    def runCmd = "docker run -d --name run-ace-${env.IMAGE_NAME} "
                    
                    if (env.REQUIRES_MQ == 'true') { runCmd += "--network mq-net " }
                    if (env.REQUIRES_REDIS == 'true') { runCmd += "--network redis-net " }
                    if (env.REQUIRES_CREDS == 'true') { 
                        runCmd += "-v ${WORKSPACE}/setdbparms.txt:/home/aceuser/initial-config/setdbparms/setdbparms.txt:ro "
                    }
                    runCmd += "app-ace-${env.IMAGE_NAME}:latest"
                    
                    // Uses lowercase logic for cleaning old instances
                    sh "docker rm -f run-ace-${env.IMAGE_NAME} || true"
                    sh "${runCmd}"
                }
            }
        }
    }
}
