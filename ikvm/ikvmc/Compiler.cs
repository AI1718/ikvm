/*
  Copyright (C) 2002 Jeroen Frijters

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
using System.Collections;
using System.IO;
using System.Reflection;
using ICSharpCode.SharpZipLib.Zip;

class Compiler
{
	private static string manifestMainClass;

	private static ArrayList GetArgs(string[] args)
	{
		ArrayList arglist = new ArrayList();
		foreach(string s in args)
		{
			if(s.StartsWith("@"))
			{
				using(StreamReader sr = new StreamReader(s.Substring(1)))
				{
					string line;
					while((line = sr.ReadLine()) != null)
					{
						arglist.Add(line);
					}
				}
			}
			else
			{
				arglist.Add(s);
			}
		}
		return arglist;
	}

	static int Main(string[] args)
	{
		System.Reflection.Emit.PEFileKinds target = System.Reflection.Emit.PEFileKinds.ConsoleApplication;
		bool guessFileKind = true;
		string assemblyname = null;
		string outputfile = null;
		string main = null;
		string defaultName = null;
		bool nojni = false;
		ArrayList classesToExclude = new ArrayList();
		ArrayList references = new ArrayList();
		ArrayList arglist = GetArgs(args);
		if(arglist.Count == 0)
		{
			Console.Error.WriteLine("usage: ikvmc [-options] <classOrJar1> ... <classOrJarN>");
			Console.Error.WriteLine();
			Console.Error.WriteLine("options:");
			Console.Error.WriteLine("    -out:<outputfile>       Specify the output filename");
			Console.Error.WriteLine("    -assembly:<name>        Specify assembly name");
			Console.Error.WriteLine("    -target:exe             Build a console executable");
			Console.Error.WriteLine("    -target:winexe          Build a windows executable");
			Console.Error.WriteLine("    -target:library         Build a library");
			Console.Error.WriteLine("    -main:<class>           Specify the class containing the main method");
			Console.Error.WriteLine("    -reference:<filespec>   Reference an assembly");
			Console.Error.WriteLine("    -recurse:<filespec>     Recurse directory and include matching files");
			Console.Error.WriteLine("    -nojni                  Do not generate JNI stub for native methods");
			Console.Error.WriteLine("    -resource:<name>=<path> Include file as Java resource");
			Console.Error.WriteLine("    -exclude:<filename>     A file containing a list of classes to exclude");
			Console.Error.WriteLine("    -debug                  Creates debugging information for the output file");
			return 1;
		}
		ArrayList classes = new ArrayList();
		Hashtable resources = new Hashtable();
		foreach(string s in arglist)
		{
			if(s[0] == '-')
			{
				if(s.StartsWith("-out:"))
				{
					outputfile = s.Substring(5);
				}
				else if(s.StartsWith("-assembly:"))
				{
					assemblyname = s.Substring(10);
				}
				else if(s.StartsWith("-target:"))
				{
					switch(s)
					{
						case "-target:exe":
							target = System.Reflection.Emit.PEFileKinds.ConsoleApplication;
							guessFileKind = false;
							break;
						case "-target:winexe":
							target = System.Reflection.Emit.PEFileKinds.WindowApplication;
							guessFileKind = false;
							break;
						case "-target:library":
							target = System.Reflection.Emit.PEFileKinds.Dll;
							guessFileKind = false;
							break;
						default:
							Console.Error.WriteLine("Warning: unrecognized option: {0}", s);
							break;
					}
				}
				else if(s.StartsWith("-main:"))
				{
					main = s.Substring(6);
				}
				else if(s.StartsWith("-reference:"))
				{
					string path = Path.GetDirectoryName(s.Substring(11));
					string[] files = Directory.GetFiles(path == "" ? "." : path, Path.GetFileName(s.Substring(11)));
					foreach(string f in files)
					{
						references.Add(f);
					}
				}
				else if(s.StartsWith("-recurse:"))
				{
					string spec = s.Substring(9);
					if(Directory.Exists(spec))
					{
						DirectoryInfo dir = new DirectoryInfo(spec);
						Recurse(classes, resources, dir, dir, "*");
					}
					else
					{
						DirectoryInfo dir = new DirectoryInfo(Path.GetDirectoryName(spec));
						Recurse(classes, resources, dir, dir, Path.GetFileName(spec));
					}
				}
				else if(s.StartsWith("-resource:"))
				{
					string[] spec = s.Substring(10).Split('=');
					if(resources.ContainsKey(spec[0]))
					{
						Console.Error.WriteLine("Warning: skipping resource (name clash): " + spec[0]);
					}
					else
					{
						using(FileStream fs = new FileStream(spec[1], FileMode.Open))
						{
							byte[] b = new byte[fs.Length];
							fs.Read(b, 0, b.Length);
							resources.Add(spec[0], b);
						}
					}
				}
				else if(s == "-nojni")
				{
					nojni = true;
				}
				else if(s.StartsWith("-exclude:"))
				{
					ProcessExclusionFile(classesToExclude, s.Substring(9));
				}
				else if(s == "-debug")
				{
					JVM.Debug = true;
				}
				else
				{
					Console.Error.WriteLine("Warning: unrecognized option: {0}", s);
				}
			}
			else
			{
				if(defaultName == null)
				{
					defaultName = Path.GetFileName(s);
				}
				string path = Path.GetDirectoryName(s);
				string[] files = Directory.GetFiles(path == "" ? "." : path, Path.GetFileName(s));
				if(files.Length == 0)
				{
					Console.Error.WriteLine("Error: file not found: {0}", s);
					return 1;
				}
				foreach(string f in files)
				{
					ProcessFile(classes, resources, null, f);
				}
			}
		}
		if(defaultName == null)
		{
			Console.Error.WriteLine("Error: at least one class or jar must be specified");
			return 1;
		}
		if(assemblyname == null)
		{
			string basename = outputfile == null ? defaultName : outputfile;
			int idx = basename.LastIndexOf('.');
			if(idx > 0)
			{
				assemblyname = basename.Substring(0, idx);
			}
			else
			{
				assemblyname = basename;
			}
		}
		if(outputfile != null && guessFileKind)
		{
			if(outputfile.ToLower().EndsWith(".dll"))
			{
				target = System.Reflection.Emit.PEFileKinds.Dll;
			}
			guessFileKind = false;
		}
		if(main == null && manifestMainClass != null)
		{
			Console.Error.WriteLine("Note: using main class {0} based on jar manifest", manifestMainClass);
			main = manifestMainClass;
		}
		// TODO add support for taking default main class from jar manifest
		try
		{
			JVM.Compile(outputfile, assemblyname, main, target, guessFileKind, (byte[][])classes.ToArray(typeof(byte[])), (string[])references.ToArray(typeof(string)), nojni, resources, (string[])classesToExclude.ToArray(typeof(string)));
			return 0;
		}
		catch(Exception x)
		{
			Console.Error.WriteLine(x);
			return 1;
		}
	}

	private static byte[] ReadFromZip(ZipFile zf, ZipEntry ze)
	{
		byte[] buf = new byte[ze.Size];
		int pos = 0;
		Stream s = zf.GetInputStream(ze);
		while(pos < buf.Length)
		{
			pos += s.Read(buf, pos, buf.Length - pos);
		}
		return buf;
	}

	private static void ProcessFile(ArrayList classes, Hashtable resources, DirectoryInfo baseDir, string file)
	{
		switch(new FileInfo(file).Extension.ToLower())
		{
			case ".class":
				using(FileStream fs = new FileStream(file, FileMode.Open))
				{
					byte[] b = new byte[fs.Length];
					fs.Read(b, 0, b.Length);
					classes.Add(b);
				}
				break;
			case ".jar":
			case ".zip":
				ZipFile zf = new ZipFile(file);
				try
				{
					foreach(ZipEntry ze in zf)
					{
						if(ze.Name.ToLower().EndsWith(".class"))
						{
							classes.Add(ReadFromZip(zf, ze));
						}
						else if(ze.Name == "META-INF/MANIFEST.MF" && manifestMainClass == null)
						{
							// read main class from manifest
							// TODO find out if we can use other information from manifest
							using(StreamReader rdr = new StreamReader(zf.GetInputStream(ze)))
							{
								string line;
								while((line = rdr.ReadLine()) != null)
								{
									if(line.StartsWith("Main-Class: "))
									{
										manifestMainClass = line.Substring(12);
										break;
									}
								}
							}
						}
						else
						{
							// if it's not a class, we treat it as a resource
							if(resources.ContainsKey(ze.Name))
							{
								Console.Error.WriteLine("Warning: skipping resource (name clash): " + ze.Name);
							}
							else
							{
								resources.Add(ze.Name, ReadFromZip(zf, ze));
							}
						}
					}
				}
				finally
				{
					zf.Close();
				}
				break;
			default:
			{
				if(baseDir == null)
				{
					Console.Error.WriteLine("Warning: unknown file type: {0}", file);
				}
				else
				{
					// include as resource
					try 
					{
						using(FileStream fs = new FileStream(file, FileMode.Open))
						{
							byte[] b = new byte[fs.Length];
							fs.Read(b, 0, b.Length);
							// HACK very lame way to extract the resource name (by chopping off the base directory)
							string name = file.Substring(baseDir.FullName.Length + 1);
							name = name.Replace('\\', '/');
							resources.Add(name, b);
						}
					}
					catch(UnauthorizedAccessException)
					{
						Console.Error.WriteLine("Warning: error reading file {0}: Access Denied", file);
					}
				}
				break;
			}
		}
	}

	private static void Recurse(ArrayList classes, Hashtable resources, DirectoryInfo baseDir, DirectoryInfo dir, string spec)
	{
		foreach(FileInfo file in dir.GetFiles(spec))
		{
			ProcessFile(classes, resources, baseDir, file.FullName);
		}
		foreach(DirectoryInfo sub in dir.GetDirectories())
		{
			Recurse(classes, resources, baseDir, sub, spec);
		}
	}

	//This processes an exclusion file with a single regular expression per line
	private static void ProcessExclusionFile(ArrayList classesToExclude, String filename)
	{
		try 
		{
			using(StreamReader file = new StreamReader(filename))
			{
				String line;
				while((line = file.ReadLine()) != null)
				{
					line = line.Trim();
					if(!line.StartsWith("//") && line.Length != 0)
					{
						classesToExclude.Add(line);
					}
				}
			}
		} 
		catch(FileNotFoundException) 
		{
			Console.Error.WriteLine("Warning: could not find exclusion file '{0}'", filename);
		}
	}
}
