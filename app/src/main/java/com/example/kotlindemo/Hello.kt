fun main() {
//    lambda to double a number
//    val doubleNum = {x : Int -> x * 2}
//    print(doubleNum(5))

//    lambda to greet a user
//    val greet = { name : String -> "Hello $name" }
//    println(greet("John Doe"))

//    lambda to check if a number is even
//    val isEven = {x : Int -> x % 2 == 0}
//    println(isEven(0))

//  lambda to square each number
//    val list = listOf(1, 3, 4, 7, 8)
//    val sqList = list.map { it * it }
//    println(sqList)

//    lambda to filter odd numbers
//    val list = listOf(3, 12, 13, 5, 6, 29)
//    val onlyOdd = list.filter { it % 2 == 1 }
//    println(onlyOdd)


//    lambda to count words starting with "a"
//    val words = listOf("ahello", "apple", "abc", "height")
//    val count = words.count { it.startsWith("a") }
//    println(count)

    val users = listOf(
        User("John Doe", 25),
        User("Hillary Bond", 17),
        User("Kayak Krook", 22),
    )

    val adults = users.filter { it.age >= 18 }.map { "${it.name} : (${it.age})" }
    println(adults)

}

data class User(val name: String, val age: Int)

