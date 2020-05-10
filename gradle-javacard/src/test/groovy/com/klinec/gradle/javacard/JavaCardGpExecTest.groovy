package com.klinec.gradle.javacard


import com.klinec.gradle.javacard.common.CommonTest
import com.klinec.gradle.javacard.gp.GpExec
import org.gradle.api.tasks.JavaExec
import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertTrue

class JavaCardGpExecTest extends CommonTest {

    @Test
    void gpExecTest() {
        JavaExec listTask = project.tasks.create(name: 'testTask', type: GpExec)

        assertTrue(listTask ? true : false)
        assertTrue(listTask instanceof JavaExec)
        assertTrue(listTask instanceof GpExec)
        assertEquals(listTask.main, 'pro.javacard.gp.GPTool')
    }
}