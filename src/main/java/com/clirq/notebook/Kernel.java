package com.clirq.notebook;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.ExpressionEvaluator;
import org.codehaus.janino.JavaSourceClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Kernel implements Closeable {
	protected static Logger logger = LoggerFactory.getLogger(Kernel.class);
	File[] cp;

	Map<String, Object> objects;
	Object[] objectsA;
	Timer timer;
	File semaphor;
	public static ExpressionEvaluator ee;

	public Kernel(File[] cp) throws IOException {
		objects = new HashMap<>();
		objectsA = new Object[] { objects };
		this.cp = cp;
		this.timer=new Timer("cells");
		this.semaphor=new File("Z:\\Tom\\stop_java");
	}

	public static void main(String[] args)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException, IllegalArgumentException,
			InvocationTargetException, NoSuchMethodException, SecurityException {
		List<File> cp = new ArrayList<>();
		for (String a : args) {
			File f=new File(a);
			if (f.exists())
				cp.add(f);
			else {
				logger.info("Argument |{}| is not an existing file", a);
			}
		}
		ee = new ExpressionEvaluator();

		try (Kernel kernel = new Kernel(cp.toArray(new File[0]))) {
			kernel.listen();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void listen() {
		Pattern implicitVar=Pattern.compile("(%([A-Za-z][A-Za-z0-9_]*))");
		Thread listener=Thread.currentThread();
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (semaphor.exists()) {
					logger.info("Found semaphore: cell running too long. Interrupting");
					if (!semaphor.delete()) {
						logger.warn("Failed to delete semaphore |{}| please delete manually", semaphor);
					}
					listener.interrupt();
				}
			}
		}, 3_000, 3_000);//wait a bit before checking for the file since it would take some time for the user to create the file anyway. 
		try (Scanner scan = new Scanner(System.in)) {
			String line = "", previousInput="";
			String methodName = null, className = null;
			System.out.println("Start listening. Type $EXIT to exit");
			while (!(line = scan.nextLine()).equals("$EXIT")) {
				if (line.trim().length() == 0) {
					line=previousInput;
				}
				if (line.startsWith("\t")) {
					String expression=line.trim();
					Matcher m = implicitVar.matcher(expression);
					expression=m.replaceAll(mr ->{
						String varName=mr.group(2);
						Object val=objects.get(varName);
						if (val!=null) {
							Class<? extends Object> clz = val.getClass();
							String newExp=String.format("(%sobjects.get(\"%s\"))", 
									clz.getName().equals("Object")?"":"("+clz.getCanonicalName()+")",varName);
							return newExp;							
						}else {
							return mr.group();
						}
					});
					System.out.format("%s ==>%n", expression);
					evaluateExpression(expression);
				} else {
					String[] inputs = line.split("\t");
					methodName = inputs[0];
					if (inputs.length > 1)
						className = inputs[1];// otherwise leave unchanged
					// A new instance of the class loader is needed to refresh the loaded class
					JavaSourceClassLoader cl = new JavaSourceClassLoader(this.getClass().getClassLoader(), // parentClassLoader
							this.cp, // optionalSourcePath
							(String) null // optionalCharacterEncoding
					);
					// debuggingInformation
					cl.setDebuggingInfo(true, true, true);

					// The qualified class name must match the folder hierarchy
					try {
						Class<?> clz = cl.loadClass(className);
						Object o = clz.getDeclaredConstructor().newInstance();
						/*
						 * Reflection instead of interface removes the dependency of the Notebook source
						 * file on this project. if (o instanceof Notebook) {
						 * ((Notebook)o).setState(objects); }
						 */
						try {

							Method setObjectsMethod = clz.getDeclaredMethod("setState", Map.class);
							setObjectsMethod.invoke(o, objects);
						} catch (NoSuchMethodException | SecurityException e) {
							// ignore - setState is not mandatory.
						}
						// Call the desired method.
						Method method = clz.getDeclaredMethod(methodName, Map.class);
						method.invoke(o, objects);						
						
					} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
							| IllegalArgumentException | InvocationTargetException | NoSuchMethodException
							| SecurityException e) {
						logger.warn("Error while tryin to invoke {}.{}: {}", className, methodName, e.getMessage());
						e.printStackTrace();
					} catch (Exception e) {
						logger.info("Exception while invoking {}.{}: {}.", className, methodName, e.getMessage());
						e.printStackTrace();
					} catch (Throwable e) {
						logger.info("Error while invoking {}.{}: {}. Consider exiting", className, methodName,
								e.getMessage());
						e.printStackTrace();
					}
				}
				previousInput=line;
				System.out.println(
						"Type <TAB><expression> or <method><TAB><class> to execute a method. Leave empty to use the current expression/method/class values. $EXIT to exit");
			}
			System.out.println("Exiting");
		}
		System.out.println("Finished");
	}

	public void evaluateExpression(String expression) {
		// Now here's where the story begins...

		// The expression will have two "int" parameters: "a" and "b".
		ee.setParameters(new String[] { "objects" }, new Class[] { Map.class });

		// And the expression (i.e. "result") type is also "int".
		ee.setExpressionType(Object.class);

		// And now we "cook" (scan, parse, compile and load) the fabulous expression.
		try {
			ee.cook(expression);
			// Eventually we evaluate the expression - and that goes super-fast.
			Object result = ee.evaluate(objectsA);
			System.out.println(result);
			logger.info("{} --> {}", expression, result);
		} catch (CompileException e) {
			logger.info("Error compiling expression |{}|: {}", expression, e.getMessage());
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			logger.info("Error executing expression |{}|: {}", expression, e.getMessage());
			e.printStackTrace();
		}

	}

	@Override
	public void close() throws IOException {
		logger.info("Closing instances");
		for (Entry<String, Object> e : objects.entrySet()) {
			Object object = e.getValue();
			if (object instanceof Closeable) {
				logger.info("Attempt closing {}", e.getKey());
				try {
					((Closeable) object).close();
				} catch (Exception e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
			}
		}
		logger.info("Finished closing instances");
	}
}
