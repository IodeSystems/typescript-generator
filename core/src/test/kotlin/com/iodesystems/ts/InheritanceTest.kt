package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

@RestController
@RequestMapping("/inherit/")
class InheritanceApi {

    open class Base(open val id: String)

    data class Child(
        override val id: String,
        val value: Int
    ) : Base(id)

    interface LivingThing {
        val alive: Boolean
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    sealed interface Animal : LivingThing {
        val philum: String

        data class Dog(
            override val philum: String,
            override val alive: Boolean,
            val woofs: Int,
        ) : Animal

        data class Cat(
            override val philum: String,
            override val alive: Boolean,
            val meows: Int,
        ) : Animal
    }

    @GetMapping("/child")
    fun getChild(): Child = error("test")

    @GetMapping("/animal")
    fun getAnimal(): Animal = error("test")
}

class InheritanceTest {

    @Test
    fun objectInheritance_isRenderedAsIntersection() {
        val em = emitter<InheritanceApi>()
        val ts = em.ts().content()

        ts.assertContains(
            fragment = """
                export type InheritanceApiBase = {
                  id: string
                }
            """.trimIndent(),
            why = "Base should be emitted as a plain object"
        )

        ts.assertContains(
            fragment = """
                export type InheritanceApiChild = InheritanceApiBase & {
                  value: number
                }
            """.trimIndent(),
            why = "Child should inherit Base using an intersection type and add its own fields"
        )

        ts.assertContains(
            fragment = """
                export class InheritanceApi {
                  constructor(private opts: ApiOptions = {}) {}
                  getChild(): Promise<InheritanceApiChild> {
            """.trimIndent(),
            why = "API should return the inherited type"
        )
    }

    @Test
    fun unionInheritance_intersectsWithBase() {
        val em = emitter<InheritanceApi>()
        val ts = em.ts().content()

        ts.assertContains(
            fragment = $$"""
                export type InheritanceApiAnimalDog = InheritanceApiAnimal & {
                  "@class": "com.iodesystems.ts.InheritanceApi$Animal$Dog"
                  woofs: number
                }
            """.trimIndent(),
            why = "Dog should be emitted as a base type"
        )
        ts.assertContains(
            fragment = """
                export type InheritanceApiLivingThing = {
                  alive: boolean
                }
            """.trimIndent(),
            why = "InheritanceApiLivingThing should be emitted"
        )

        ts.assertContains(
            fragment = """
                export type InheritanceApiAnimalUnion = InheritanceApiAnimal & (InheritanceApiAnimalCat | InheritanceApiAnimalDog)
            """.trimIndent(),
            why = "InheritanceApiAnimalUnion"
        )

        ts.assertContains(
            fragment = """
             export type InheritanceApiAnimal = InheritanceApiLivingThing & {
               philum: string
             }
            """.trimIndent(),
            why = "InheritanceApiAnimal"
        )

        ts.assertContains(
            fragment = """
                  getAnimal(): Promise<InheritanceApiAnimalUnion> {
            """.trimIndent(),
            why = "Method signature"
        )

        ts.assertContains(
            fragment = $$"""
               export type InheritanceApiAnimalCat = InheritanceApiAnimal & {
                 "@class": "com.iodesystems.ts.InheritanceApi$Animal$Cat"
                 meows: number
               }
            """.trimIndent(),
            why = "Union children should inherit the interface of their Union intersection."
        )
    }
}
