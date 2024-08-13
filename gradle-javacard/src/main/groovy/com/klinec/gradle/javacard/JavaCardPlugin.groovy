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

package com.klinec.gradle.javacard

import com.klinec.gradle.javacard.extension.Applet
import com.klinec.gradle.javacard.extension.JavaCard
import com.klinec.gradle.javacard.gp.GpExec
import com.klinec.gradle.javacard.util.SdkUtils
import com.klinec.gradle.javacard.util.Utility
import org.gradle.api.DefaultTask
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
    static String DELETE_TASK = 'deleteJavaCard'
    static String PRE_CARD_TASK = 'preJavaCard'

    static String GLOBAL_PLATFORM_GROUP = 'global platform'
    static String GLOBAL_PLATFORM_HELPER_GROUP = 'global platform helpers'

    Task buildTask

    void apply(Project project) {

        //define plugin extension
        def extension = project.extensions.create(PLUGIN_NAME, JavaCard)

        project.configurations {
            jcardsim
            sctest
            sdk
            gptool
        }

        project.afterEvaluate {

            //validate the extension properties
            extension.validate(project)

            File propertyFile = project.rootProject.file('local.properties')

            Properties properties = new Properties()
            if (propertyFile.exists()) {
                properties.load(propertyFile.newDataInputStream())
                if (properties.getProperty('jc.home')?.trim()) {
                    extension.config.jckit = properties.getProperty('jc.home')
                }
            }

            if (!extension.config.jcardSim) {
                extension.config.jcardSim = getJcardSim(properties)
            }

            if (!extension.config.gptoolVersion) {
                extension.config.gptoolVersion = getGpToolVersion(properties)
            }

            logger.info("jckit location : ${extension.config.getJcKit()}")
            logger.info("jcardsim: ${extension.config.getJcardSim()}")
            logger.info("gptool version: ${extension.config.getGptoolVersion()}")

            configureClasspath(project, extension)

            if (extension.scripts != null) {

                extension.scripts.tasks.each { taskItem ->

                    def command = []
                    if (extension.config.isDebugGpPro()) {
                        command.add('-d')
                    }

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

            if (!project.tasks.findByName(LIST_TASK)) {
                createListTask(project, extension)
            }

            if (!project.tasks.findByName(INSTALL_TASK)) {
                createInstallTask(project, extension)
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

    static def getDefaultJcardSim() {
        return 'com.klinec:jcardsim:3.0.5.11'
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
     * Tries to determine jcardsim version to use
     * @param properties
     * @return
     */
    def getJcardSim(properties){
        if (System.env['JCARDSIM_VER']?.trim()) {
            return System.env['JCARDSIM_VER']
        }

        if (properties.getProperty('jcardsim.ver')?.trim()) {
            return properties.getProperty('jcardsim.ver')
        }

        if (System.getProperty("jcardsim.ver")?.trim()){
            return System.getProperty("jcardsim.ver")
        }

        return 'com.klinec:jcardsim:3.0.5.11'
    }

    def getGpToolVersion(properties){
        if (properties.getProperty('gptool.ver')?.trim()) {
            return properties.getProperty('gptool.ver')
        }

        if (System.getProperty("gptool.ver")?.trim()){
            return System.getProperty("gptool.ver")
        }

        return '20.08.12'
    }

    /**
     * Configure source set / dependency class path for main, tests and smartcard test
     *
     * @param project gradle project
     * @param sdk JC SDK path
     * @return
     */
    def configureClasspath(Project project, JavaCard extension) {
        if (extension.config.addSurrogateJcardSimRepo) {
            logger.warn("addSurrogateJcardSimRepo option is deprecated and has no effect")
        }

        project.configurations.gptool.defaultDependencies {
            it.add(project.dependencies.create("com.github.martinpaljak:gppro:${extension.config.getGptoolVersion()}"))
            it.add(project.dependencies.create("com.github.martinpaljak:gptool:${extension.config.getGptoolVersion()}"))
            it.add(project.dependencies.create("com.github.martinpaljak:globalplatformpro:${extension.config.getGptoolVersion()}"))
        }

        project.configurations.jcardsim.defaultDependencies {
            it.add(project.dependencies.create(extension.config.getJcardSim()))
        }

        def testClasspath = project.configurations.jcardsim + project.files(new File(GPTool.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()))

        def sdkPath = project.files(SdkUtils.getApiPath(extension.config.getJcKit(), logger))
        project.sourceSets {
            test {
                compileClasspath.from(testClasspath)
                runtimeClasspath.from(testClasspath)
            }
        }

        extension.config.caps.each { capItem ->
            if (capItem.dependencies != null) {
                capItem.dependencies.local.each { localItem ->
                    project.dependencies.add("implementation", project.files(localItem.jar))
                }
                capItem.dependencies.remote.each { remoteItem ->
                    project.dependencies.add("implementation", remoteItem)
                }
            }
        }

        //resolve the javacard framework according to SDK version
        project.dependencies {
            sdk sdkPath
            if (hasDependencies(extension)) {
                extension.test.dependencies.dependencies.each() { dep ->
                    jcardsim dep
                }

                if (extension.config.addImplicitJcardSimJunit){
                    logger.info("addImplicitJcardSimJunit is overridden by test dependencies configuration")
                }
                if (extension.config.addImplicitJcardSim){
                    logger.info("addImplicitJcardSim is overridden by test dependencies configuration")
                }

            } else {
                if (!extension.config.addImplicitJcardSimJunit) {
                    logger.info("addImplicitJcardSimJunit disabled junit inclusion");
                } else {
                    jcardsim getDefaultJunit()
                }

                if (!extension.config.addImplicitJcardSim) {
                    logger.info("addImplicitJcardSim disabled jcardsim inclusion")
                } else {
                    jcardsim extension.config.getJcardSim()
                }
            }

            testImplementation testClasspath

            // CompileOnly is required otherwise it gets added to the tests as well
            // where it clashes with the JCardSim
            compileOnly sdkPath
        }

        // Remove JC API for test runtime classpath as JCardSim embeds own version and we need to have it first
        logger.info("SDK path: ${sdkPath.getAsPath()}")
        project.sourceSets.test.runtimeClasspath = project.sourceSets.test.runtimeClasspath.filter {
            (it.path != sdkPath.getAsPath()) }

        // Removal of the JC API from the test.compileClasspath
        if (extension.config.fixClassPath) {
            // project.sourceSets.test.compileClasspath is of type org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection
            // type can be infered by `.getClass()`
            def gfrom = project.sourceSets.test.compileClasspath.getFrom()
            logger.debug("Classpath getFrom() is: ${gfrom.getClass()}")
            logger.debug("Classpath getFrom() contents: ${gfrom.join(':')}")

            // This class path fix breaks modules dependencies as the list looses meta information
            // about dependencies. In future release inspect how to reorder DefaultConfiguration
            // and move JC API to the end of the list.
            // Or: add JC API to the classpath as the last element here (omit compileOnly sdkPath)
            def compileTestCp = new ArrayList<File>(project.sourceSets.test.compileClasspath.getFiles().asList())
            def idxSdk = compileTestCp.findIndexOf { it.path == sdkPath.getAsPath() }
            def idxSim = compileTestCp.findIndexOf { it.path.contains("jcardsim") }
            if (idxSdk >= 0 && idxSim >= 0 && idxSim > idxSdk) {
                logger.debug("Test compile classpath indices: sdk: ${idxSdk} jcardsim: ${idxSim}, cp: ${compileTestCp.join(':')}")
                compileTestCp.remove(idxSdk)
                compileTestCp.add(idxSim, sdkPath)

                logger.debug("Test compile classpath pre: ${compileTestCp}")
                project.sourceSets.test.compileClasspath = project.files(compileTestCp)
                logger.debug("Test compile classpath post: ${project.sourceSets.test.compileClasspath.join(':')}")
                logger.warn("ClassPath fix active, tests.compileClassPath was modified, information about project dependencies are lost. " +
                        "Thus IDEs such as Idea can have problems importing the project with module dependencies. " +
                        "To disable the classpath fix define javacard.config.fixClassPath false. ")
            }
        }

        logger.debug("Main compile classpath: ${project.sourceSets.main.compileClasspath.join(':')}")
        logger.debug("Main runtime classpath: ${project.sourceSets.main.runtimeClasspath.join(':')}")
        logger.debug("Test compile classpath: ${project.sourceSets.test.compileClasspath.join(':')}")
        logger.debug("Test runtime classpath: ${project.sourceSets.test.runtimeClasspath.join(':')}")

        project.test.testLogging {
            events "passed", "skipped", "failed"
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
        def args = []
        def delTasks = []

        def preCardTask = project.tasks.create(name: PRE_CARD_TASK, type: DefaultTask)
        createTask(preCardTask, GLOBAL_PLATFORM_HELPER_GROUP, "Empty task called before card manipulation (for hooks)")

        extension.config.caps.eachWithIndex { capItem, capIdx ->
            File file = new File(capItem.output)
            String file2Add
            if (!file.isAbsolute()) {
                file2Add = new File("${project.buildDir.absolutePath}${File.separator}javacard${File.separator}${capItem.output}").absolutePath
            } else {
                file2Add = new File(capItem.output).absolutePath
            }

            // Deletion tasks (package, applets)
            def curDelTasksSpecs = []
            def curDelTasks = []

            // Delete each applet first
            capItem.applets.eachWithIndex { Applet appletItem, int idx ->
                def lstCls = appletItem.className.split("\\.").last().capitalize()
                def taskName = "${DELETE_TASK}Package${String.format("%02d", capIdx)}Applet${String.format("%02d", idx)}$lstCls"
                def tmpArgs = ["--delete", appletItem.aid.replace(":", "")]
                def taskDesc = "Removes applet \"${appletItem.className}\" (${appletItem.aid}) from the card"
                curDelTasksSpecs.add(new Tuple3(taskName, tmpArgs, taskDesc))
            }

            // Then the package
            def pkgAbrev = capItem.packageName.split("\\.").last().capitalize()
            curDelTasksSpecs.add(new Tuple3(
                    "${DELETE_TASK}Package${String.format("%02d", capIdx)}$pkgAbrev",
                    ["--delete", capItem.aid.replace(":", "")],
                    "Removes package \"${capItem.packageName}\" (${capItem.aid}) from the card"))

            // Create tasks from specs, the last one if package del
            curDelTasksSpecs.eachWithIndex { tpl, taskIdx ->
                def (taskName, tmpArgs, taskDesc) = tpl
                tmpArgs = Utility.addKeyArg(extension.key, extension.defaultKey, tmpArgs)
                tmpArgs = Utility.addGpProArgs(extension, tmpArgs)
                tmpArgs = Utility.addAuxGpProArgs(extension.config.installGpProArgs, tmpArgs)

                def tmpTask = project.tasks.create(name: taskName, type: GpExec)
                tmpTask.setIgnoreExitValue(true)

                createGpExec(tmpTask, GLOBAL_PLATFORM_HELPER_GROUP, taskDesc, tmpArgs)
                curDelTasks.add(tmpTask)
            }

            // Make package del depend on all applets del
            def pkgDelTask = curDelTasks.last()
            pkgDelTask.mustRunAfter preCardTask
            curDelTasks[0 ..< (curDelTasks.size()-1)].each { ctask ->
                pkgDelTask.dependsOn ctask
                ctask.mustRunAfter preCardTask
            }

            // Package del aggregation
            delTasks.add(pkgDelTask)

            args.add('--install')
            args.add(file2Add)
        }

        def pkgDel = project.tasks.create(name: "${DELETE_TASK}Packages", type: DefaultTask)
        createTask(pkgDel, GLOBAL_PLATFORM_GROUP, "Removes all packages from the card")

        pkgDel.dependsOn preCardTask
        delTasks.each { it ->
            pkgDel.dependsOn it
        }

        def install = project.tasks.create(name: INSTALL_TASK, type: GpExec)
        install.dependsOn buildTask
        install.dependsOn preCardTask
        install.dependsOn pkgDel

        args = Utility.addKeyArg(extension.key, extension.defaultKey, args)
        args = Utility.addGpProArgs(extension, args)
        args = Utility.addAuxGpProArgs(extension.config.installGpProArgs, args)
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
        args = Utility.addGpProArgs(extension, args)

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

    def createTask(Task task, String grp, String desc) {
        task.configure {
            group = grp
            description = desc
        }
    }
}
