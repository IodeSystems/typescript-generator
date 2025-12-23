package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

class NonSealedUnionTest {

    @RestController
    @RequestMapping
    class NonSealedController {
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
        @JsonSubTypes(
            JsonSubTypes.Type(value = NonSealedAnimalDog::class, name = "dog"),
            JsonSubTypes.Type(value = NonSealedAnimalCat::class, name = "cat")
        )
        interface Animal {
            val name: String
        }

        @PostMapping("/animal")
        fun postAnimal(@RequestBody animal: Animal) = Unit
    }

    @Test
    fun nonSealedInterfaceWithJsonSubTypes() {
        val emitter = emitter(NonSealedController::class)
        val result = emitter.ts().content()

        // Verify the parent interface is emitted
        result.assertContains(
            fragment = """
            export type NonSealedUnionTestNonSealedControllerAnimal = {
              name: string
            }
            """.trimIndent(),
            "Animal interface should be emitted"
        )

        // Verify Dog child type is emitted with discriminator
        result.assertContains(
            fragment = """
              "type": "dog"
            """.trimIndent(),
            "Dog should have discriminator field with value 'dog'"
        )

        result.assertContains(
            fragment = """
              breed: string
            """.trimIndent(),
            "Dog should have breed field"
        )

        // Verify Cat child type is emitted with discriminator
        result.assertContains(
            fragment = """
              "type": "cat"
            """.trimIndent(),
            "Cat should have discriminator field with value 'cat'"
        )

        result.assertContains(
            fragment = """
              livesLeft: number
            """.trimIndent(),
            "Cat should have livesLeft field"
        )

        // Verify the union type is created
        result.assertContains(
            fragment = "NonSealedUnionTestNonSealedControllerAnimalUnion",
            "Union type should be created"
        )

        // Verify the union contains both children (Cat comes before Dog alphabetically)
        result.assertContains(
            fragment = "NonSealedAnimalCat | NonSealedAnimalDog",
            "Union should contain Cat | Dog"
        )

        // Verify the API method uses the union type
        result.assertContains(
            fragment = "postAnimal(req: NonSealedUnionTestNonSealedControllerAnimalUnion)",
            "POST method should accept union type"
        )
    }
}

// Child classes in a different package-like namespace (separate top-level classes)
data class NonSealedAnimalDog(
    override val name: String,
    val breed: String
) : NonSealedUnionTest.NonSealedController.Animal

data class NonSealedAnimalCat(
    override val name: String,
    val livesLeft: Int
) : NonSealedUnionTest.NonSealedController.Animal

// Generic test classes
@RestController
@RequestMapping
class GenericNonSealedController {
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes(
        JsonSubTypes.Type(value = GenericStringContainer::class, name = "string"),
        JsonSubTypes.Type(value = GenericIntContainer::class, name = "int")
    )
    interface Container<T> {
        val value: T
    }

    @PostMapping("/container")
    fun postContainer(@RequestBody container: Container<*>) = Unit
}

data class GenericStringContainer(override val value: String) : GenericNonSealedController.Container<String>
data class GenericIntContainer(override val value: Int) : GenericNonSealedController.Container<Int>
