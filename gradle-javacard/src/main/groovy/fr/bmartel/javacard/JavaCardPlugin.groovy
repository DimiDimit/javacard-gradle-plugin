/*
 * The MIT License (MIT)
 * <p/>
 * Copyright (c) 2017-2018 Bertrand Martel
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package fr.bmartel.javacard

import fr.bmartel.javacard.extension.JavaCard
import fr.bmartel.javacard.gp.GpExec
import fr.bmartel.javacard.util.SdkUtils
import fr.bmartel.javacard.util.Utility
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import pro.javacard.gp.GPTool

/**
 * JavaCard plugin.
 *
 * @author Bertrand Martel
 */
class JavaCardPlugin implements Plugin<Project> {

    Logger logger = LoggerFactory.getLogger('javacard-logger')

    static String PLUGIN_NAME = 'javacard'

    static String LIST_TASK = 'listJavaCard'
    static String INSTALL_TASK = 'installJavaCard'
    static String BUILD_TASK = 'buildJavaCard'

    static String GLOBAL_PLATFORM_GROUP = 'global platform'

    Task buildTask

    void apply(Project project) {

        //define plugin extension
        def extension = project.extensions.create(PLUGIN_NAME, JavaCard)

        project.configurations {
            jcardsim
            sctest
            sdk
        }

        project.afterEvaluate {

            //validate the extension properties
            extension.validate(project)

            initDependencies(project)

            File propertyFile = project.rootProject.file('local.properties')

            Properties properties = new Properties()
            if (propertyFile.exists()) {
                properties.load(propertyFile.newDataInputStream())
                if (properties.getProperty('jc.home')?.trim()) {
                    extension.config.jckit = properties.getProperty('jc.home')
                }
            }
            logger.debug("jckit location : ${extension.config.getJcKit()}")

            configureClasspath(project, extension)

            if (extension.scripts != null) {

                extension.scripts.tasks.each { taskItem ->

                    def command = []

                    command.add('-d')

                    taskItem.scripts.each { taskIncludedScript ->
                        extension.scripts.scripts.each { scriptItem ->
                            if (scriptItem.name == taskIncludedScript) {
                                command.add('-a')
                                command.add(Utility.formatByteArray(scriptItem.apdu))
                            }
                        }
                    }

                    if (!project.tasks.findByName(taskItem.name)) {
                        createScriptTask(project, taskItem.name, command)
                    }
                }
            }

            if (!project.tasks.findByName(INSTALL_TASK)) {
                createInstallTask(project, extension)
            }

            if (!project.tasks.findByName(LIST_TASK)) {
                createListTask(project, extension)
            }
        }

        //apply the java plugin if not defined
        if (!project.plugins.hasPlugin(JavaPlugin)) {
            project.plugins.apply(JavaPlugin)
        }

        buildTask = project.tasks.create(BUILD_TASK, JavaCardBuildTask)

        buildTask.configure {
            group = 'build'
            description = 'Create CAP file(s) for installation on a smart card'
        }

        //add property : javacard output directory
        project.ext.javacardDir = "${project.buildDir.absolutePath}${File.separator}javacard"
    }

    static def initDependencies(Project project) {
        project.repositories.add(project.repositories.mavenCentral())
    }

    static def getDefaultJcardSim() {
        return 'com.licel:jcardsim:3.0.4'
    }

    static def getDefaultJunit() {
        return 'junit:junit:4.12'
    }

    static def hasDependencies(JavaCard extension) {
        if (extension.test != null &&
                extension.test.dependencies != null &&
                extension.test.dependencies.dependencies.size() > 0) {
            return true
        }
        return false
    }

    /**
     * Configure source set / dependency class path for main, tests and smartcard test
     *
     * @param project gradle project
     * @param sdk JC SDK path
     * @return
     */
    def configureClasspath(Project project, JavaCard extension) {

        if (!hasDependencies(extension) &&
                !project.repositories.findByName("jcardsim")) {
            def buildRepo = project.repositories.maven {
                name 'jcardsim'
                url "http://dl.bintray.com/bertrandmartel/maven"
            }
            project.repositories.add(buildRepo)
            logger.debug("jcardsim repo added")
        }

        def testClasspath = project.configurations.jcardsim + project.files(new File(GPTool.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()))

        def sdkPath = project.files(SdkUtils.getApiPath(extension.config.getJcKit(), logger))

        project.sourceSets {
            main {
                compileClasspath += project.configurations.sdk
            }
            test {
                compileClasspath += testClasspath
                runtimeClasspath += testClasspath
            }
        }

        //resolve the javacard framework according to SDK version
        project.dependencies {
            sdk sdkPath

            if (hasDependencies(extension)) {
                extension.test.dependencies.dependencies.each() { dep ->
                    jcardsim dep
                }
            } else {
                jcardsim getDefaultJunit()
                jcardsim getDefaultJcardSim()
            }
            compile sdkPath

            testCompile testClasspath
        }

        project.test.testLogging {
            events "passed", "skipped", "failed"
        }

        extension.config.caps.each { capItem ->

            if (capItem.dependencies != null) {
                capItem.dependencies.local.each { localItem ->
                    project.dependencies.add("compile", project.files(localItem.jar))
                }
                capItem.dependencies.remote.each { remoteItem ->
                    project.dependencies.add("compile", remoteItem)
                }
            }
        }
    }

    /**
     * create GpExec install cap file task
     *
     * @param project gradle project
     * @param extension gradle extension
     * @return
     */
    def createInstallTask(Project project, JavaCard extension) {
        def install = project.tasks.create(name: INSTALL_TASK, type: GpExec)
        def args = ['--relax']
        extension.config.caps.each { capItem ->
            args.add('--delete')
            args.add(Utility.formatByteArray(capItem.aid))
            args.add('--install')

            File file = new File(capItem.output)
            if (!file.isAbsolute()) {
                args.add(new File("${project.buildDir.absolutePath}${File.separator}javacard${File.separator}${capItem.output}").absolutePath)
            } else {
                args.add(new File(capItem.output).absolutePath)
            }
        }

        install.dependsOn buildTask

        args = Utility.addKeyArg(extension.key, extension.defaultKey, args)

        createGpExec(install, GLOBAL_PLATFORM_GROUP, 'install cap file', args)
    }

    /**
     * Create GpExec list applet task
     *
     * @param project gradle project
     * @return
     */
    def createListTask(Project project, JavaCard extension) {

        def args = ['-l']

        args = Utility.addKeyArg(extension.key, extension.defaultKey, args)

        def script = project.tasks.create(name: LIST_TASK, type: GpExec)
        createGpExec(script, GLOBAL_PLATFORM_GROUP, 'list applets', args)
    }

    /**
     * Create GpExec apdu script task.
     *
     * @param project gradle project
     * @param taskName task name
     * @param args
     * @return
     */
    def createScriptTask(Project project, String taskName, args) {
        def script = project.tasks.create(name: taskName, type: GpExec)
        createGpExec(script, GLOBAL_PLATFORM_GROUP, 'apdu script', args)
    }

    /**
     * Create GpExec task
     *
     * @param project gradle project
     * @param task gradle task object
     * @param grp group name
     * @param desc task description
     * @param arguments arguments to gp tool
     * @return
     */
    def createGpExec(Task task, String grp, String desc, arguments) {
        task.configure {
            group = grp
            description = desc
            args(arguments)
            doFirst {
                println("gp ${arguments}")
            }
        }
    }
}