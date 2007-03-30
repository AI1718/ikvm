/*
  Copyright (C) 2002, 2004, 2005, 2006, 2007 Jeroen Frijters

  This software is provided 'as-is', without any express or implied
  warranty.  In no event will the authors be held liable for any damages
  arising from the use of this software.

  Permission is granted to anyone to use this software for any purpose,
  including commercial applications, and to alter it and redistribute it
  freely, subject to the following restrictions:

  1. The origin of this software must not be misrepresented; you must not
     claim that you wrote the original software. If you use this software
     in a product, an acknowledgment in the product documentation would be
     appreciated but is not required.
  2. Altered source versions must be plainly marked as such, and must not be
     misrepresented as being the original software.
  3. This notice may not be removed or altered from any source distribution.

  Jeroen Frijters
  jeroen@frijters.net
  
*/
using System;
using System.Reflection;
using System.IO;
using System.Text;
using System.Collections;
using IKVM.Attributes;
using java.util.zip;

public class NetExp
{
	private static ZipOutputStream zipFile;
	private static Hashtable done = new Hashtable();
	private static Hashtable todo = new Hashtable();
	private static FileInfo file;

	public static void Main(string[] args)
	{
		IKVM.Internal.Tracer.EnableTraceForDebug();
		if(args.Length != 1)
		{
			Console.Error.WriteLine(ikvm.runtime.Startup.getVersionAndCopyrightInfo());
			Console.Error.WriteLine();
			Console.Error.WriteLine("usage: ikvmstub <assemblyNameOrPath>");
			return;
		}
		Assembly assembly = null;
		try
		{
			file = new FileInfo(args[0]);
		}
		catch(System.Exception x)
		{
			Console.Error.WriteLine("Error: unable to load \"{0}\"\n  {1}", args[0], x.Message);
			return;
		}
		if(file != null && file.Exists)
		{
#if WHIDBEY
			AppDomain.CurrentDomain.ReflectionOnlyAssemblyResolve += new ResolveEventHandler(CurrentDomain_ReflectionOnlyAssemblyResolve);
			assembly = Assembly.ReflectionOnlyLoadFrom(args[0]);
#else
			try
			{
				// If the same assembly can be found in the "Load" context, we prefer to use that
				// http://blogs.gotdotnet.com/suzcook/permalink.aspx/d5c5e14a-3612-4af1-a9b7-0a144c8dbf16
				// We use AssemblyName.FullName, because otherwise the assembly will be loaded in the
				// "LoadFrom" context using the path inside the AssemblyName object.
				assembly = Assembly.Load(AssemblyName.GetAssemblyName(args[0]).FullName);
				Console.Error.WriteLine("Warning: Assembly loaded from {0} instead", assembly.Location);
			}
			catch
			{
			}
			if(assembly == null)
			{
				// since we're loading the assembly in the LoadFrom context, we need to hook the AssemblyResolve event
				AppDomain.CurrentDomain.AssemblyResolve += new ResolveEventHandler(CurrentDomain_AssemblyResolve);
				assembly = Assembly.LoadFrom(args[0]);
			}
#endif
		}
		else
		{
			assembly = Assembly.LoadWithPartialName(args[0]);
		}
		int rc = 0;
		if(assembly == null)
		{
			Console.Error.WriteLine("Error: Assembly \"{0}\" not found", args[0]);
		}
		else
		{
			using(zipFile = new ZipOutputStream(new java.io.FileOutputStream(assembly.GetName().Name + ".jar")))
			{
				zipFile.setComment(ikvm.runtime.Startup.getVersionAndCopyrightInfo());
				try
				{
					ProcessAssembly(assembly);
				}
				catch(ReflectionTypeLoadException x)
				{
					Console.WriteLine(x);
					Console.WriteLine("LoaderExceptions:");
					foreach (Exception n in x.LoaderExceptions)
					{
						Console.WriteLine(n);
					}
				}
				catch(System.Exception x)
				{
					java.lang.Throwable.instancehelper_printStackTrace(ikvm.runtime.Util.mapException(x));
					rc = 1;
				}
			}
		}
		// FXBUG if we run a static initializer that starts a thread, we would never end,
		// so we force an exit here
		Environment.Exit(rc);
	}

#if WHIDBEY
	private static Assembly CurrentDomain_ReflectionOnlyAssemblyResolve(object sender, ResolveEventArgs args)
	{
		//Console.WriteLine("Resolve: " + args.Name);
		foreach(Assembly a in AppDomain.CurrentDomain.ReflectionOnlyGetAssemblies())
		{
			if(args.Name.StartsWith(a.GetName().Name + ", "))
			{
				return a;
			}
		}
		Assembly asm = Assembly.ReflectionOnlyLoad(args.Name);
		if(asm != null)
		{
			return asm;
		}
		string path = args.Name;
		int index = path.IndexOf(',');
		if(index > 0)
		{
			path = path.Substring(0, index);
		}
		path = file.DirectoryName + Path.DirectorySeparatorChar + path + ".dll";
		Console.WriteLine("Loading referenced assembly: " + path);
		return Assembly.ReflectionOnlyLoadFrom(path);
	}
#endif

	private static void WriteClass(string name, byte[] buf)
	{
		zipFile.putNextEntry(new ZipEntry(name));
		zipFile.write(buf, 0, buf.Length);
	}

	private static void ProcessAssembly(Assembly assembly)
	{
		foreach(Type t in assembly.GetTypes())
		{
			if(t.IsPublic)
			{
				java.lang.Class c;
				try
				{
					// NOTE we use GetClassFromTypeHandle instead of GetFriendlyClassFromType, to make sure
					// we don't get the remapped types when we're processing System.Object, System.String,
					// System.Throwable and System.IComparable.
					// NOTE we can't use GetClassFromTypeHandle for ReflectionOnly assemblies
					// (because Type.TypeHandle is not supported by ReflectionOnly types), but this
					// isn't a problem because mscorlib is never loaded in the ReflectionOnly context.
#if WHIDBEY
					if(assembly.ReflectionOnly)
					{
						c = ikvm.runtime.Util.getFriendlyClassFromType(t);
					}
					else
					{
						c = ikvm.runtime.Util.getClassFromTypeHandle(t.TypeHandle);
					}
#else
					c = ikvm.runtime.Util.getClassFromTypeHandle(t.TypeHandle);
#endif
					if (c == null)
					{
						Console.WriteLine("Skipping: " + t.FullName);
						continue;
					}
				}
				catch(java.lang.ClassNotFoundException)
				{
					// types that IKVM doesn't support don't show up
					continue;
				}
				AddToExportList(c);
			}
		}
		bool keepGoing;
		do
		{
			keepGoing = false;
			foreach(java.lang.Class c in new ArrayList(todo.Values))
			{
				if(!done.ContainsKey(c.getName()))
				{
					keepGoing = true;
					done.Add(c.getName(), null);
					ProcessClass(c);
				}
			}
		} while(keepGoing);
	}

	private static void AddToExportList(java.lang.Class c)
	{
		while(c.isArray())
		{
			c = c.getComponentType();
		}
		todo[c.getName()] = c;
	}

	private static bool IsGenericType(java.lang.Class c)
	{
#if WHIDBEY
		Type t = ikvm.runtime.Util.getInstanceTypeFromClass(c);
		while(t == null && c.getDeclaringClass() != null)
		{
			// dynamic only inner class, so we look at the declaring class
			c = c.getDeclaringClass();
			t = ikvm.runtime.Util.getInstanceTypeFromClass(c);
		}
		return t.IsGenericType;
#else
		return c.getName().IndexOf("$$0060") > 0;
#endif
	}

	private static void ProcessClass(java.lang.Class c)
	{
		string name = c.getName().Replace('.', '/');
		if(c.getSuperclass() != null)
		{
			// if the base class isn't public, we still need to export it (!)
			if(!java.lang.reflect.Modifier.isPublic(c.getSuperclass().getModifiers()))
			{
				AddToExportList(c.getSuperclass());
			}
		}
		java.lang.Class[] interfaces = c.getInterfaces();
		for(int i = 0; i < interfaces.Length; i++)
		{
			if(IsGenericType(interfaces[i])
				|| !java.lang.reflect.Modifier.isPublic(interfaces[i].getModifiers()))
			{
				AddToExportList(interfaces[i]);
			}
		}
		java.lang.Class outerClass = c.getDeclaringClass();
		if(outerClass != null)
		{
			AddToExportList(outerClass);
		}
		java.lang.Class[] innerClasses = c.getDeclaredClasses();
		for(int i = 0; i < innerClasses.Length; i++)
		{
			Modifiers mods = (Modifiers)innerClasses[i].getModifiers();
			if((mods & (Modifiers.Public | Modifiers.Protected)) != 0)
			{
				AddToExportList(innerClasses[i]);
			}
		}
		java.lang.reflect.Constructor[] constructors = c.getDeclaredConstructors();
		for(int i = 0; i < constructors.Length; i++)
		{
			Modifiers mods = (Modifiers)constructors[i].getModifiers();
			if((mods & (Modifiers.Public | Modifiers.Protected)) != 0)
			{
				// TODO what happens if one of the argument types is non-public?
				java.lang.Class[] args = constructors[i].getParameterTypes();
				foreach(java.lang.Class arg in args)
				{
					// TODO if arg is not public, add it to the export list as well
					if(IsGenericType(arg))
					{
						AddToExportList(arg);
					}
				}
			}
		}
		java.lang.reflect.Method[] methods = c.getDeclaredMethods();
		for(int i = 0; i < methods.Length; i++)
		{
			// FXBUG (?) .NET reflection on java.lang.Object returns toString() twice!
			// I didn't want to add the work around to CompiledTypeWrapper, so it's here.
			if((c.getName() == "java.lang.Object" || c.getName() == "java.lang.Throwable")
				&& methods[i].getName() == "toString")
			{
				bool found = false;
				for(int j = 0; j < i; j++)
				{
					if(methods[j].getName() == "toString")
					{
						found = true;
						break;
					}
				}
				if(found)
				{
					continue;
				}
			}
			Modifiers mods = (Modifiers)methods[i].getModifiers();
			if((mods & (Modifiers.Public | Modifiers.Protected)) != 0)
			{
				// TODO what happens if one of the argument types (or the return type) is non-public?
				java.lang.Class[] args = methods[i].getParameterTypes();
				foreach(java.lang.Class arg in args)
				{
					// TODO if arg is not public, add it to the export list as well
					if(IsGenericType(arg))
					{
						AddToExportList(arg);
					}
				}
				java.lang.Class retType = methods[i].getReturnType();
				// TODO if retType is not public, add it to the export list as well
				if(IsGenericType(retType))
				{
					AddToExportList(retType);
				}
			}
		}
		java.lang.reflect.Field[] fields = c.getDeclaredFields();
		for(int i = 0; i < fields.Length; i++)
		{
			Modifiers mods = (Modifiers)fields[i].getModifiers();
			if((mods & (Modifiers.Public | Modifiers.Protected)) != 0)
			{
				java.lang.Class fieldType = fields[i].getType();
				if(IsGenericType(fieldType) || (fieldType.getModifiers() & (int)Modifiers.Public) == 0)
				{
					AddToExportList(fieldType);
				}
			}
		}
		java.io.InputStream inp = c.getResourceAsStream("/" + name + ".class");
		if(inp == null)
		{
			Console.Error.WriteLine("Class {0} not found", name);
			return;
		}
		byte[] buf = new byte[inp.available()];
		if(inp.read(buf) != buf.Length || inp.read() != -1)
		{
			throw new NotImplementedException();
		}
		WriteClass(name + ".class", buf);
	}

	private static Assembly CurrentDomain_AssemblyResolve(object sender, ResolveEventArgs args)
	{
		foreach(Assembly asm in AppDomain.CurrentDomain.GetAssemblies())
		{
			if(asm.FullName == args.Name)
			{
				return asm;
			}
		}
		return null;
	}
}
