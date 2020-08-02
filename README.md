# The New Decaf Compiler Project


Decaf is a Java-like, but much smaller programming language mainly for educational purpose.

The *Compiler's Theory* course team of Tsinghua University has provided the basic version of the Decaf compiler and the TacVM, see https://github.com/decaf-lang/decaf/releases. Based on their contribution, I am able to develop more features of the Decaf language.

## My Development:

1. **Abstract classes and methods** and its inheritance logic.
2. **First-class functions**, including Lambda expressions, closures, functional variables, method references.
3. **Automatic type inference** when declaring variables and references. Can automatically infer builtin types, reference types and first-class function types.
4. You can start a **coroutine** with a lightweight grammar the same as Golang. TacVM is upgraded to a multi-coroutine version. It can schedule coroutines using *Round Robin* strategy.
5. **Mutex lock** is supported natively, and it's easy to wrap it to develop various locks, like RW-locks, reentrant locks, semaphores and so on.


## Getting Started

This project requires JDK 12.

Other dependencies will be automatically downloaded from the maven central repository by the build script.

## Build

First install the latest version (>= 5.4) [gradle](https://gradle.org).

Type the standard Gradle build command in your CLI:

```sh
gradle build
```

The built jar will be located at `build/libs/decaf.jar`.

## Run

In your CLI, type

```sh
java -jar --enable-preview build/libs/decaf.jar -h
```

to display the usage help.

Possible targets/tasks are:

- PA1: parse source code and output the pretty printed tree, or error messages
- PA2: type check and output the pretty printed scopes, or error messages
- PA3: generate TAC (three-address code), dump it to a .tac file, and then output the execution result using our built-in simulator

Typically, just type
```sh
java -jar --enable-preview build/libs/decaf.jar ${dir}/${filename}.decaf -t PA3
```
to generate the TAC program and run it with TacVM.


## Some Interesting Examples

1. Printing "ABCABCABC..." in turn by 3 coroutines.

```java
class CondVar {
    int value;
    void init() { value = 0; }
    int getValue() { return value; }
    void flap() { value = (value+1)%3; }
}
class Test {
    class CondVar cv;
    void foo(int id, string name) {
        int cnt = 0;
        while (cnt < 10) {
            lock(1);
            if (cv.getValue() == id) {
                Print(name);
                cv.flap();
                cnt = cnt + 1;
            }
            unlock(1);
        }
    }
    void start() {
        cv = new CondVar();
        go foo(0, "A"); // starting 3 coroutines
        go foo(1, "B");
        go foo(2, "C");
    }
}
class Main {
    static void main() {
        class Test test = new Test();
        test.start();
    }
}
```

- result

```
ABCABCABCABCABCABCABCABCABCABC
```

2. Capability of dealing with **enclosures** and **type inference** of anonymous functions

```java
class Data {
	int data;
	void setData(int data) { this.data = data; }
	int getData() { return data; }
}

class Test {
	int() getData(int u, int v) {  // This method returns a function that returns a+b+c
		class Data a = new Data();
		a.setData(u);
		
		var foo = fun(int y) {
			
			class Data b = new Data();
			b.setData(y);
			
			var bar = fun() {
				return fun() => a.getData() + b.getData(); // Captures "a" and "b" from different scopes
			};
			
			return bar();
		};
		
		return fun() => foo(v)(); // Return a function created inside "foo"
	}
}

class Main {
    static void main() {
		class Test test = new Test();
		var foo = test.getData(12, 34);
		Print(foo());
	}
}
```

- result

```
46
```

## Future Work

1. Advanced coroutine scheduling strategies may be added in the future.

2. Currently TacVM is binded with the compiler, which means it's also written in Java. It's strange that a VM is implemented with another VM-needed language. So it may be reimplemented in a native language like C++/Rust.

