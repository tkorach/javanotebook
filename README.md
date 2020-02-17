# javanotebook
An IDE-based notebook for Java allowing step-by-step debugging, editing in file and expression evaluation
Please see the [Eclipse plugin](https://github.com/tkorach/cellrunner) to enable one-click execution. 

# Quick start
1. See *Recommended setup* below to set up the project and IDE. 
1. Create a notebook:
*Notebook.java*
```java
public class Notebook {
  public Notebook(){ }
  public static void cell(Map<String, Object> objects){
    System.out.println("Hello, world!");
  }
 }
 ```
 1. Compile the file `javac Notebook.java`
 1. Copy the generated .class file to the kernel's source directory. 
 1. Start the kernel (if not started).
 1. type to standard input `A<tab>cell`
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
One of the goals of interactive programming is to preserve the state (variable values and objects) between commands issued by the users. Similar to Python's `globals` scope, `javanotbook` allows storing object instances in a global `public static Map<String, Object>` container available to all classes.

## Kernel
Similar to Python's Jupyter execution happens in a **Kernel** instance: a class that instantiates the global container and awaits commands from the user from the standard input (an Eclipse plugin is also available). It can executes three kinds of commands: 

### 1. Evaluate expression:
Read an expression from standard input and evaluate it as a Java code, using the [Janino](http://janino-compiler.github.io/janino/) script excutor. It allows referencing objects from the global container using `%` prefix. However, Janino does not support generics.

### 2. Execute a method from a class file:
The kernel tracks one or more file system directories. Whenever a the users asks to execute a method (by specifying its fully qualified name), the kernel:
  1. Loads all the classes in the tracked directory(ies). 
  1. Instantiate the desired class. 
  1. Invokes the desired method.

The classes in the tracked directories are called *notebooks*, and the invoked methods are called *cells*. Each tracked directory serves as a root, and the class files' location must match the package hierarchy. E.g., if `/home/user/classes` is a tracked directory, the class `com.acme.Notebook1` should be placed: 

- /home/user/classes
-- com
--- acme
---- Notebook1.class

The classes in the tracked directories can be generated in any way. The typical set-up is to point the kernel to the output directory of an Eclipse project. The source files are edited in Eclipse, which re-compiles the classes after each change, so the kernel sees the updated version.

Notebooks (invoked classes) need to:
  1. **Must** Have a public no-argument constructor. 
  1. Can have a `public void setState(Map<String, object> objects)` method which will be invoked prior to the cell to set the class's instance's state cell execution (see *Class loading and state preservation* below).
  
Cells (invoked methods) must:
  1. Have the `public` modifier.
  1. Have a single `Map` argument. When invoked, the kernel will pass global container to this argument. 

Any return value will be discarded. The invoked method can persist its result or state by storing it in the global container. 

### 3. Invoke the `main` method of a class:
Using `main <tab> <fully qualified class name> <tab> <command-line arguments>`. The `main` metho will be invoked in a new thread. The regular signature of a `main` method is expected. The global state container will be accessible via a `public static final Map<Thread, Map<String, Object> objectsByThread` member of the `com.clirq.notebook.Kernel` class. Since the notebooks themselves do not necessarily have the Kernel as a dependency, it can be retrieved using reflection (see below for a zero-dependency code snippet that can be copied to each notebook). 

```java
public static Map<String, Object> getKernelObjectsMap() throws ClassNotFoundException, NoSuchFieldException,
			SecurityException, IllegalArgumentException, IllegalAccessException {
		return getKernelObjectsMap("com.clirq.notebook.Kernel", "objectsByThread");
	}

	public static Map<String, Object> getKernelObjectsMap(String className, String mapName)
			throws ClassNotFoundException, NoSuchFieldException, SecurityException, IllegalArgumentException,
			IllegalAccessException {
		Class<?> cls = Class.forName(className);
		java.lang.reflect.Field fld = cls.getField(mapName);
		Map<Thread, Map<String, Object>> objectsByThread = (Map<Thread, Map<String, Object>>) fld.get(null);
		Map<String, Object> m = objectsByThread.get(Thread.currentThread());
		return m;
	}
 ```

### Shutting down a kernel
The kernel can be gracefully shut-down by entering `$EXIT` command in the standard input. The kernel will attempt to close any object in the global container that implements the `java.io.Closeable` interface and then interrupt all execution threads. If the threads do not respond, the JVM has to be terminated manually. 

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
  public static void cell_1(Map<String, Object> objects){
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
The classes that are expected to be modified interactively (i.e. notebooks). All of these classes shoudl follow the requirements for notebooks listed above. To preserve their state across invocations: 

1. All **members** must be immutable classes. 
1. A `public void setState(Map<String, object> objects)` should be created to restore the state. The member object should be create on the first invocation and retireved from the map on subsequent invocations.  A typical pattern is to use `java.util.map.computeIfAbsent` to compute in the first time and get the cached value on the next times: 
```java
public class Notebook {
  String member;
  public void setState(Map<String, object> objects){
    member = (String) objects.computeIfAbsent("member", k-> return "expensive to instantiate object");
  }
  
  public Notebook(){ }
}
```
If a notebook needs to instantiate another notebook, create an instance using the no-argument constructor and then invoke the `setState` method: 
```java
public class NotebookContainingNotebook {
  Notebook anotherNotebook;
  
public void setState(Map<String, object> objects){
    anotherNotebook = (Notebook) objects.computeIfAbsent("anotherNotebook", k-> {
      Notebook n = new Notebook();
      n.setState(objects);
      return n;
     });
  }
  
  public NotebookContainingNotebook(){ }
  
}
```

# Recommended setup 
This is the setup I use in my daily work. Differnet IDEs or arrangements might be possible - suggestions are welcomed. 

Steps:

1. Clone the repository. 
1. Add to Eclipse workspace as a Java (Maven) project. 
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
The Notebook classes are valid Java classes. In my experience, productization involved mainly converting the `setState` to a full-argument constructor and writing a `main` method to initiate execution. 

# Issues
Please report any issues or questions in the "Issues" tab and tag me.

# Contributions
Contributions are welcomed. The wish-list include:
1. Supporting additionals IDEs.
1. Remote execution (like Jupyter Notebook). 
1. Variable explorer
