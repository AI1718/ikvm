using System;
using System.Xml.Serialization;
using System.Collections;
using System.Reflection;
using System.Reflection.Emit;
using System.Diagnostics;
using OpenSystem.Java;

namespace MapXml
{
	public abstract class Instruction
	{
		internal abstract void Generate(Hashtable context, ILGenerator ilgen);
	}

	[XmlType("ldstr")]
	public sealed class Ldstr : Instruction
	{
		[XmlAttribute("value")]
		public string Value;

		internal override void Generate(Hashtable context, ILGenerator ilgen)
		{
			ilgen.Emit(OpCodes.Ldstr, Value);
		}
	}

	[XmlType("ldnull")]
	public sealed class Ldnull : Simple
	{
		public Ldnull() : base(OpCodes.Ldnull)
		{
		}
	}

	[XmlType("call")]
	public class Call : Instruction
	{
		public Call() : this(OpCodes.Call)
		{
		}

		internal Call(OpCode opcode)
		{
			this.opcode = opcode;
		}

		[XmlAttribute("class")]
		public string Class;
		[XmlAttribute("type")]
		public string type;
		[XmlAttribute("name")]
		public string Name;
		[XmlAttribute("sig")]
		public string Sig;

		private CodeEmitter emitter;
		private OpCode opcode;

		internal sealed override void Generate(Hashtable context, ILGenerator ilgen)
		{
			if(emitter == null)
			{
				Debug.Assert(Name != null);
				if(Name == ".ctor")
				{
					Debug.Assert(Class == null && type != null);
					Type[] argTypes = ClassLoaderWrapper.GetBootstrapClassLoader().ArgTypeListFromSig(Sig);
					emitter = CodeEmitter.Create(opcode, Type.GetType(type, true).GetConstructor(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance, null, CallingConventions.Standard, argTypes, null));
				}
				else
				{
					Debug.Assert(Class == null ^ type == null);
					if(Class != null)
					{
						Debug.Assert(Sig != null);
						MethodWrapper method = ClassLoaderWrapper.GetBootstrapClassLoader().LoadClassByDottedName(Class).GetMethodWrapper(MethodDescriptor.FromNameSig(ClassLoaderWrapper.GetBootstrapClassLoader(), Name, Sig), false);
						if(method != null)
						{
							if(opcode.Value == OpCodes.Call.Value)
							{
								emitter = method.EmitCall;
							}
							else if(opcode.Value == OpCodes.Callvirt.Value)
							{
								emitter = method.EmitCallvirt;
							}
							else if(opcode.Value == OpCodes.Newobj.Value)
							{
								emitter = method.EmitNewobj;
							}
							else
							{
								throw new InvalidOperationException();
							}
						}
						// TODO this code is part of what Compiler.CastInterfaceArgs (in compiler.cs) does,
						// it would be nice if we could avoid this duplication...
						TypeWrapper[] argTypeWrappers = method.Descriptor.ArgTypeWrappers;
						for(int i = 0; i < argTypeWrappers.Length; i++)
						{
							if(argTypeWrappers[i].IsGhost)
							{
								LocalBuilder[] temps = new LocalBuilder[argTypeWrappers.Length + (method.IsStatic ? 0 : 1)];
								for(int j = temps.Length - 1; j >= 0; j--)
								{
									TypeWrapper tw;
									if(method.IsStatic)
									{
										tw = argTypeWrappers[j];
									}
									else
									{
										if(j == 0)
										{
											tw = method.DeclaringType;
										}
										else
										{
											tw = argTypeWrappers[j - 1];
										}
									}
									if(tw.IsGhost)
									{
										tw.EmitConvStackToParameterType(ilgen, tw);
									}
									temps[j] = ilgen.DeclareLocal(tw.TypeAsParameterType);
									ilgen.Emit(OpCodes.Stloc, temps[j]);
								}
								for(int j = 0; j < temps.Length; j++)
								{
									ilgen.Emit(OpCodes.Ldloc, temps[j]);
								}
								break;
							}
						}
					}
					else
					{
						if(Sig != null)
						{
							Type[] argTypes = ClassLoaderWrapper.GetBootstrapClassLoader().ArgTypeListFromSig(Sig);
							emitter = CodeEmitter.Create(opcode, Type.GetType(type, true).GetMethod(Name, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static, null, argTypes, null));
						}
						else
						{
							emitter = CodeEmitter.Create(opcode, Type.GetType(type, true).GetMethod(Name, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static));
						}
					}
				}
				if(emitter == null)
				{
					// TODO better error handling
					throw new InvalidOperationException("method not found: " + Name);
				}
			}
			emitter.Emit(ilgen);
		}
	}

	[XmlType("callvirt")]
	public sealed class Callvirt : Call
	{
		public Callvirt() : base(OpCodes.Callvirt)
		{
		}
	}

	[XmlType("newobj")]
	public sealed class NewObj : Call
	{
		public NewObj() : base(OpCodes.Newobj)
		{
		}
	}

	public abstract class Simple : Instruction
	{
		private OpCode opcode;

		public Simple(OpCode opcode)
		{
			this.opcode = opcode;
		}

		internal sealed override void Generate(Hashtable context, ILGenerator ilgen)
		{
			ilgen.Emit(opcode);
		}
	}

	[XmlType("dup")]
	public sealed class Dup : Simple
	{
		public Dup() : base(OpCodes.Dup)
		{
		}
	}

	[XmlType("pop")]
	public sealed class Pop : Simple
	{
		public Pop() : base(OpCodes.Pop)
		{
		}
	}

	public abstract class TypeOrTypeWrapperInstruction : Instruction
	{
		[XmlAttribute("class")]
		public string Class;
		[XmlAttribute("type")]
		public string type;

		internal TypeWrapper typeWrapper;
		internal Type typeType;

		internal override void Generate(Hashtable context, ILGenerator ilgen)
		{
			if(typeWrapper == null && typeType == null)
			{
				Debug.Assert(Class == null ^ type == null);
				if(Class != null)
				{
					typeWrapper = ClassLoaderWrapper.GetBootstrapClassLoader().LoadClassByDottedName(Class);
				}
				else
				{
					typeType = Type.GetType(type, true);
				}
			}
		}
	}

	[XmlType("isinst")]
	public sealed class IsInst : TypeOrTypeWrapperInstruction
	{
		public IsInst()
		{
		}
	
		// TODO isinst is broken, because for Java types it returns true/false while for
		// .NET types it returns null/reference.
		internal override void Generate(Hashtable context, ILGenerator ilgen)
		{
			base.Generate(context, ilgen);
			if(typeType != null)
			{
				ilgen.Emit(OpCodes.Isinst, typeType);
			}
			else
			{
				// NOTE we pass a null context, but that shouldn't be a problem, because
				// typeWrapper should never be an UnloadableTypeWrapper
				typeWrapper.EmitInstanceOf(null, ilgen);
			}
		}
	}

	[XmlType("castclass")]
	public sealed class Castclass : TypeOrTypeWrapperInstruction
	{
		public Castclass()
		{
		}

		internal override void Generate(Hashtable context, ILGenerator ilgen)
		{
			base.Generate(context, ilgen);
			if(typeType != null)
			{
				ilgen.Emit(OpCodes.Castclass, typeType);
			}
			else
			{
				// NOTE we pass a null context, but that shouldn't be a problem, because
				// typeWrapper should never be an UnloadableTypeWrapper
				typeWrapper.EmitCheckcast(null, ilgen);
			}
		}
	}

	public abstract class TypeInstruction : Instruction
	{
		[XmlAttribute("type")]
		public string type;

		private OpCode opcode;
		private Type typeType;

		internal TypeInstruction(OpCode opcode)
		{
			this.opcode = opcode;
		}

		internal override void Generate(Hashtable context, ILGenerator ilgen)
		{
			if(typeType == null)
			{
				Debug.Assert(type != null);
				typeType = Type.GetType(type, true);
			}
			ilgen.Emit(opcode, typeType);
		}
	}

	[XmlType("unbox")]
	public sealed class Unbox : TypeInstruction
	{
		public Unbox() : base(OpCodes.Unbox)
		{
		}
	}

	[XmlType("box")]
	public sealed class Box : TypeInstruction
	{
		public Box() : base(OpCodes.Box)
		{
		}
	}

	public abstract class Branch : Instruction
	{
		private OpCode opcode;

		public Branch(OpCode opcode)
		{
			this.opcode = opcode;
		}

		internal sealed override void Generate(Hashtable context, ILGenerator ilgen)
		{
			Label l;
			if(context[Name] == null)
			{
				l = ilgen.DefineLabel();
				context[Name] = l;
			}
			else
			{
				l = (Label)context[Name];
			}
			ilgen.Emit(opcode, l);
		}

		[XmlAttribute("name")]
		public string Name;
	}

	[XmlType("brfalse")]
	public sealed class BrFalse : Branch
	{
		public BrFalse() : base(OpCodes.Brfalse)
		{
		}
	}

	[XmlType("brtrue")]
	public sealed class BrTrue : Branch
	{
		public BrTrue() : base(OpCodes.Brtrue)
		{
		}
	}

	[XmlType("br")]
	public sealed class Br : Branch
	{
		public Br() : base(OpCodes.Br)
		{
		}
	}

	[XmlType("label")]
	public sealed class BrLabel : Instruction
	{
		[XmlAttribute("name")]
		public string Name;

		internal override void Generate(Hashtable context, ILGenerator ilgen)
		{
			Label l;
			if(context[Name] == null)
			{
				l = ilgen.DefineLabel();
				context[Name] = l;
			}
			else
			{
				l = (Label)context[Name];
			}
			ilgen.MarkLabel(l);
		}
	}

	[XmlType("stloc")]
	public sealed class StLoc : Instruction
	{
		[XmlAttribute("name")]
		public string Name;
		[XmlAttribute("class")]
		public string Class;
		[XmlAttribute("type")]
		public string type;

		private TypeWrapper typeWrapper;
		private Type typeType;

		internal override void Generate(Hashtable context, ILGenerator ilgen)
		{
			LocalBuilder lb = (LocalBuilder)context[Name];
			if(lb == null)
			{
				if(typeWrapper == null && typeType == null)
				{
					Debug.Assert(Class == null ^ type == null);
					if(type != null)
					{
						typeType = Type.GetType(type, true);
					}
					else
					{
						typeWrapper = ClassLoaderWrapper.GetBootstrapClassLoader().LoadClassByDottedName(Class);
					}
				}
				lb = ilgen.DeclareLocal(typeType != null ? typeType : typeWrapper.TypeAsTBD);
				context[Name] = lb;
			}
			ilgen.Emit(OpCodes.Stloc, lb);
		}
	}

	[XmlType("ldloc")]
	public sealed class LdLoc : Instruction
	{
		[XmlAttribute("name")]
		public string Name;
		
		internal override void Generate(Hashtable context, ILGenerator ilgen)
		{
			ilgen.Emit(OpCodes.Ldloc, (LocalBuilder)context[Name]);
		}
	}

	[XmlType("ldarg_0")]
	public sealed class LdArg_0 : Simple
	{
		public LdArg_0() : base(OpCodes.Ldarg_0)
		{
		}
	}

	[XmlType("ldarg_1")]
	public sealed class LdArg_1 : Simple
	{
		public LdArg_1() : base(OpCodes.Ldarg_1)
		{
		}
	}

	[XmlType("ldarg_2")]
	public sealed class LdArg_2 : Simple
	{
		public LdArg_2() : base(OpCodes.Ldarg_2)
		{
		}
	}

	[XmlType("ldarg_3")]
	public sealed class LdArg_3 : Simple
	{
		public LdArg_3() : base(OpCodes.Ldarg_3)
		{
		}
	}

	[XmlType("ldind_i4")]
	public sealed class Ldind_i4 : Simple
	{
		public Ldind_i4() : base(OpCodes.Ldind_I4)
		{
		}
	}

	[XmlType("ret")]
	public sealed class Ret : Simple
	{
		public Ret() : base(OpCodes.Ret)
		{
		}
	}

	[XmlType("throw")]
	public sealed class Throw : Simple
	{
		public Throw() : base(OpCodes.Throw)
		{
		}
	}

	[XmlType("stsfld")]
	public sealed class Stsfld : Instruction
	{
		[XmlAttribute("class")]
		public string Class;
		[XmlAttribute("name")]
		public string Name;
		[XmlAttribute("sig")]
		public string Sig;

		internal override void Generate(Hashtable context, ILGenerator ilgen)
		{
			ClassLoaderWrapper.LoadClassCritical(Class).GetFieldWrapper(Name, ClassLoaderWrapper.GetBootstrapClassLoader().FieldTypeWrapperFromSig(Sig)).EmitSet.Emit(ilgen);
		}
	}

	[XmlType("ldc_i4_0")]
	public sealed class Ldc_I4_0 : Simple
	{
		public Ldc_I4_0() : base(OpCodes.Ldc_I4_0)
		{
		}
	}

	public class InstructionList : CodeEmitter
	{
		[XmlElement(typeof(Ldstr))]
		[XmlElement(typeof(Call))]
		[XmlElement(typeof(Callvirt))]
		[XmlElement(typeof(Dup))]
		[XmlElement(typeof(Pop))]
		[XmlElement(typeof(IsInst))]
		[XmlElement(typeof(Castclass))]
		[XmlElement(typeof(Unbox))]
		[XmlElement(typeof(Box))]
		[XmlElement(typeof(BrFalse))]
		[XmlElement(typeof(BrTrue))]
		[XmlElement(typeof(Br))]
		[XmlElement(typeof(BrLabel))]
		[XmlElement(typeof(NewObj))]
		[XmlElement(typeof(StLoc))]
		[XmlElement(typeof(LdLoc))]
		[XmlElement(typeof(LdArg_0))]
		[XmlElement(typeof(LdArg_1))]
		[XmlElement(typeof(LdArg_2))]
		[XmlElement(typeof(LdArg_3))]
		[XmlElement(typeof(Ldind_i4))]
		[XmlElement(typeof(Ret))]
		[XmlElement(typeof(Throw))]
		[XmlElement(typeof(Ldnull))]
		[XmlElement(typeof(Stsfld))]
		[XmlElement(typeof(Ldc_I4_0))]
		public Instruction[] invoke;

		internal sealed override void Emit(ILGenerator ilgen)
		{
			Hashtable context = new Hashtable();
			for(int i = 0; i < invoke.Length; i++)
			{
				invoke[i].Generate(context, ilgen);
			}
		}
	}

	public class Throws
	{
		[XmlAttribute("class")]
		public string Class;
	}

	public class Constructor
	{
		[XmlAttribute("sig")]
		public string Sig;
		[XmlAttribute("modifiers")]
		public MapModifiers Modifiers;
		public InstructionList body;
		public InstructionList alternateBody;
		public Redirect redirect;
		[XmlElement("throws", typeof(Throws))]
		public Throws[] throws;
	}

	public class Redirect
	{
		[XmlAttribute("class")]
		public string Class;
		[XmlAttribute("name")]
		public string Name;
		[XmlAttribute("sig")]
		public string Sig;
		[XmlAttribute("type")]
		public string Type;
	}

	public class Override
	{
		[XmlAttribute("name")]
		public string Name;
	}

	public class Method
	{
		[XmlAttribute("name")]
		public string Name;
		[XmlAttribute("sig")]
		public string Sig;
		[XmlAttribute("modifiers")]
		public MapModifiers Modifiers;
		[XmlAttribute("type")]
		public string Type;
		public InstructionList body;
		public InstructionList alternateBody;
		public Redirect redirect;
		public Override @override;
		[XmlElement("throws", typeof(Throws))]
		public Throws[] throws;
	}

	public class Field
	{
		[XmlAttribute("name")]
		public string Name;
		[XmlAttribute("sig")]
		public string Sig;
		[XmlAttribute("modifiers")]
		public MapModifiers Modifiers;
		[XmlAttribute("constant")]
		public string Constant;
		public Redirect redirect;
	}

	public class Interface
	{
		[XmlAttribute("class")]
		public string Name;
	}

	[Flags]
	public enum MapModifiers
	{
		[XmlEnum("public")]
		Public = Modifiers.Public,
		[XmlEnum("protected")]
		Protected = Modifiers.Protected,
		[XmlEnum("private")]
		Private = Modifiers.Private,
		[XmlEnum("final")]
		Final = Modifiers.Final,
		[XmlEnum("interface")]
		Interface = Modifiers.Interface,
		[XmlEnum("static")]
		Static = Modifiers.Static,
		[XmlEnum("abstract")]
		Abstract = Modifiers.Abstract
	}

	[XmlType("class")]
	public class Class
	{
		[XmlAttribute("name")]
		public string Name;
		[XmlAttribute("type")]
		public string Type;
		[XmlAttribute("modifiers")]
		public MapModifiers Modifiers;
		[XmlElement("constructor")]
		public Constructor[] Constructors;
		[XmlElement("method")]
		public Method[] Methods;
		[XmlElement("field")]
		public Field[] Fields;
		[XmlElement("implements")]
		public Interface[] Interfaces;
		[XmlElement("box")]
		public InstructionList Box;
		[XmlElement("clinit")]
		public Method Clinit;
	}

	[XmlType("exception")]
	public class ExceptionMapping
	{
		[XmlAttribute]
		public string src;
		[XmlAttribute]
		public string dst;
		public InstructionList code;
	}

	[XmlRoot("root")]
	public class Root
	{
		public Class[] remappings;
		public Class[] nativeMethods;
		public ExceptionMapping[] exceptionMappings;
	}
}
