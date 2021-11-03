#tl;dr: 
Unlimited hot-code replacement for Java. Interactive programming (like Jupyter) with all the capabilities of IDE like step-by-step debugging and code completion.

# javanotebook
An IDE-based notebook for Java allowing step-by-step debugging, editing in file. 
Please see the [Eclipse plugin](https://github.com/tkorach/cellrunner) to enable one-click execution. 

# Quick start
1. See *Recommended setup* below to set up the project and IDE. 
1. Create a notebook:
*Notebook.java*
```java
public class Notebook {
  public Notebook(){ }
  public static void cell(){
    System.out.println("Hello, world!");
  }
 }
 ```
 1. Compile the file `javac Notebook.java`
 1. Copy the generated .class file to the kernel's source directory. 
 1. Start the kernel (if not started).
 1. type to standard input `cell<tab>Notebook`
 Expected output: 
 *Hellow, world!*
 
# Motivation
Read-eval-print loop (REPL) is a long-awaited feature that was added to Java 9. In other languages, REPL's basic functionality was later extended to allow editing and executing larger chunks of codes, like cells in Jupyter Notebook. Java is (for good and bad) a verbose language. I found that JShell and BeakerX were inconvenient to write and debug Java code beyond short snippets. I was looking for a way to interactively edit, executes and debug code spanning multiple methods or classes. 
This tool extends the functionality to interactively execute whole files, enabling:

1. Executing Java code interactively: the JVM continues to run, preserving its state (e.g. objects) between user commands and avoiding the JVM's slow start-up.
1. Using an IDE (e.g. Eclipse), with all of its utilities (code completion, refactoring, source lookup etc.)
1. Step-by-step debugging (stepping through code as it is executed). 
1. One-click execution of full methods or lines.  


# Architecture
As opposed to languages like Python, REPL was not available in Java from the beginning. As Java classes are compiled before starting the JVM (rather than interpreted on the fly as in Python), changing their code in runtime ("hot code replacement", HCR) is more complex. In Eclipse, [HCR only works when the class signature does not change; you cannot remove or add fields to existing classes](https://wiki.eclipse.org/FAQ_What_is_hot_code_replace%3F). This severly limits interactive coding. 

## State preservation
One of the goals of interactive programming is to preserve the state (variable values and objects) between commands issued by the users. `javanotbook` automaticall preserves the objects' state across code changes and reconstruct it each time a command is issued. 

## Kernel
Similar to Python's Jupyter execution happens in a **Kernel**: a class that manages the runtime and can execute new code entered by the user. `javanotbook` allow the user to freely change 

### 2. Execute a method from a class file:
The kernel tracks one or more file system directories. Whenever a the users asks to execute a method (by specifying its fully qualified name), the kernel:
  1. (Re-)Loads all the classes in the tracked directory(ies). 
  1. (Re-)Instantiate the desired class. 
  2. Copy the previous instance's state to the new instance of this class.  
  3. Invokes the desired method.

The classes in the tracked directories are called *notebooks*, and the invoked methods are called *cells*. Each tracked directory serves as a root, and the class files' location must match the package hierarchy. E.g., if `/home/user/classes` is a tracked directory, the class `com.acme.Notebook1` should be placed: 

- /home/user/classes
-- com
--- acme
---- Notebook1.class

The classes in the tracked directories can be generated in any way. The typical set-up is to point the kernel to the output directory of an Maven/Eclipse/IDEA project. The source files are edited in the IDE, which re-compiles the classes after each change, so the kernel sees the updated version.

Notebooks (invoked classes) need to:
  1. **Must** Have a public no-argument constructor. 
  1. Have only `public` non-final members (see *Class loading and state preservation* below).

Only a single instance of the class can be preserved at any given time.
  
Cells (invoked methods) must:
  1. Have the `public` modifier.
  1. Have no arguments.. 

Any return value will be discarded. The invoked method can persist its result or state by storing it as an class member.  

### Shutting down a kernel
The kernel can be gracefully shut-down by entering `$EXIT` command in the standard input. The kernel will attempt to close any object in the tracked class hierarchy that implements the `java.io.Closeable` interface and then interrupt all execution threads. If the threads do not respond, the JVM has to be terminated manually. 

## Class loading and state preservation
In Java, each loaded class is tied to its class loader (see (http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html) for more background). Instances of the same class loaded by different loaders will be considered different types and will be incompatible for assignment. Thus, interactive programming poses two conflicting needs:
1. Reload a new version of the class (using a new class loader).
2. Re-use existing objects by instances of the new version. 

For example, assume we have two notebooks:

*A.java*
```java
public class A {
  B b;
  public A(){ }
  public static void cell_1(){
    this.b = new B("some value that takes a long time to create");
    objects.put("b", b); //persist b for the next invocation. 
  }
 }
 ```
 
 *B.java*
 ```java
 public class B {
  public String string;
  public B(String string){
    this.string = string;
   }
 }
 ```
When executing `cell_1`, a new class loader (*cl1*) is instantiated and loads both A and B classes. The instance of `B` created in `cell_1` belongs to `cl1`. 
We now modify A: 

*A.java*
```java
public class A {
  B b;
  public A(){ }
  
  public void cell_2(){
    this.b = (B)objects.get("b");
    System.out.println("b.b = " + b.b);
  }
  public static void cell_1(Map<String, Object> objects){
    this.b = new B("some value that takes a long time to create");
    objects.put("b", b); //persist b for the next invocation. 
  }
 }
 ```
When `cell_2` is invoked, a new class loader `cl2` is instantiated by the kernel and loads both the modified `A` class and all other classes. The line 
`this.b = (B)objects.get("b");` 
will throw an error. Even though class `B` was not changed, the `B b` member in class `A` now refers to the copy of `B` loaded by `cl2`, while the instance in the `Map<String, Object> objects` container belongs to the copy of `B` that was loaded by `cl1`. These two loaded copies of the class, despite having the same content, are considered different and incompatible. Therefore, to re-use objects from a previous cell invocation, classes have to be organized appropriately. 

## Organizing classes for interactive programming
The classes of the program shall be divided to two groups: 

### 1. Immutable classes
These classes cannot be modified during the JVM's lifetime, but their instances can be re-used across cell invocations. This scope should include the "building blocks" of the modified classes, e.g. Java's standard libraries (collections etc.) and external project dependencies. 

### 2. Mutable classes
The classes that are expected to be modified interactively (i.e. notebooks). All of these classes should follow the requirements for notebooks listed above. The kernel will recursively copy the members of each class from the previous instance to the new instance:
1. Immutable classes: simple copy. 
2. Mutable classes: instantiate the newly loaded copy of the class (from the new class loader) and populate its members. 

# Recommended setup 
This is the setup I use in my daily work. Differnet IDEs or arrangements might be possible - suggestions are welcomed. 

Steps:

1. Clone the repository. 
1. Add to Eclipse workspace/IDEA project as a Java (Maven) project/module. 
1. Edit the properties file to point to the target directory of another project in the workspace.
1. Edit the project's `pom.xml` file and any dependencies (will be included as immutable classes).  
1. Create a launch configuration, pointing to the `com.clirq.notebook.Kernel` class and listing the path to the properties file as the sole command line argument.
1. Edit the launch configuration's source setting --> "sources" tab, and add the source folder of the **other workplace project** (whose target directory is listed in the properties file). This is needed to support step-by-step debugging. 
1. Install the [Eclipse plugin](https://github.com/tkorach/cellrunner) to allow invoking each cell with a hotkey.
1. Run the launch configuration. 
1. Edit the notebooks and invoke cells. 

# Advanced topics
## Concurrent cell invocation
Each cell is executed in its own thread. Currently, the kernel awaits the invoked cell to finish and does not allow invoking multiple cells concurrently. However, each cell can execute concurrently in the JVM's usual manner.

## Interrupting cells
Sometimes a running cell has to be interrupted. Instead of killing the whole JVM, the kernel provides a mechanism to ask the thread to stop. To stop a cell, create a file (can be empty) at the path listed in the configuration file's `semaphore` attribute. The kernel checks for the presence of each file at the frequency listed in the `semaphoreListenerFrequency`, starting once the time specified in the `semaphoreListenerInitialDelay` has passed since starting the JVM, both values in milliseconds.

Since the JVM provides no mechanism to *forcibly stop* a thread, the cell must explicitly check the `Thread.interrupted()` flag and terminate accordingly. Typical locations for such check are loops and long-running operations. 

## Productization
The Notebook classes are valid Java classes. In my experience, productization involved just encapsulating the `public` fields, adding `main` method to initiate execution and a full-argument constructor. 

# Issues
Please report any issues or questions in the "Issues" tab and tag me.

# Contributions
Contributions are welcomed. The wish-list include:
1. Supporting additionals IDEs.
1. Remote execution (like Jupyter Notebook). 

