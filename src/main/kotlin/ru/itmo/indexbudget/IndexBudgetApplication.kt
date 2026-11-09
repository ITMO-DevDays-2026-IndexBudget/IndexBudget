package ru.itmo.indexbudget

import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import ru.itmo.indexbudget.cli.IndexBudgetCli
import kotlin.system.exitProcess

@SpringBootApplication
class IndexBudgetApplication

fun main(args: Array<String>) {
    val application = SpringApplication(IndexBudgetApplication::class.java).apply {
        setWebApplicationType(WebApplicationType.NONE)
        setAddCommandLineProperties(false)
    }
    val exitCode = application.run(*args).use { context ->
        context.getBean(IndexBudgetCli::class.java).execute(args)
    }
    exitProcess(exitCode)
}
