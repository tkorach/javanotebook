package com.clirq.notebook;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;

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
	public static ExpressionEvaluator ee;

	public Kernel(File[] cp) {
		objects = new HashMap<>();
		objectsA = new Object[] { objects };
		this.cp = cp;
	}

	public static void main(String[] args)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException, IllegalArgumentException,
			InvocationTargetException, NoSuchMethodException, SecurityException {
		File[] cp = { new File(".\\src\\main\\java\\bwh\\mterms\\notebook"), new File("F:\\programs\\nbs"),
				new File("F:\\git\\mterms\\java\\phrasemining\\src\\main\\java") };
		ee = new ExpressionEvaluator();

		try (Kernel kernel = new Kernel(cp)) {
			kernel.listen();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void listen() {
		try (Scanner scan = new Scanner(System.in)) {
			String line = "";
			String methodName = null, className = null;
			System.out.println("Start listening. Type $EXIT to exit");
			while (!(line = scan.nextLine()).equals("$EXIT")) {
				if (line.startsWith("\t")) {
					String expression=line.trim();
					evaluateExpression(expression);
				} else if (line.trim().length() > 0) {
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
				System.out.println(
						"Type <TAB><expression> or <method><TAB><class> to execute a method. Leave empty to use current method/class values. $EXIT to exit");
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
