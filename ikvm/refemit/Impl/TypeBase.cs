﻿/*
  Copyright (C) 2008 Jeroen Frijters

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

namespace IKVM.Reflection.Emit.Impl
{
	public abstract class TypeBase : Type
	{
#if NET_4_0
		public abstract override Assembly Assembly
		{
			get;
		}
#else
		public sealed override Assembly Assembly
		{
			get { throw new NotSupportedException(); }
		}
#endif

		public abstract override string AssemblyQualifiedName
		{
			get;
		}

		public abstract override Type BaseType
		{
			get;
		}

		public abstract override string FullName
		{
			get;
		}

		public sealed override Guid GUID
		{
			get { throw new NotSupportedException(); }
		}

		protected abstract override TypeAttributes GetAttributeFlagsImpl();

		protected sealed override ConstructorInfo GetConstructorImpl(BindingFlags bindingAttr, Binder binder, CallingConventions callConvention, Type[] types, ParameterModifier[] modifiers)
		{
			throw new NotSupportedException();
		}

		public sealed override ConstructorInfo[] GetConstructors(BindingFlags bindingAttr)
		{
			throw new NotSupportedException();
		}

		public override Type GetElementType()
		{
			return null;
		}

		public sealed override EventInfo GetEvent(string name, BindingFlags bindingAttr)
		{
			throw new NotSupportedException();
		}

		public sealed override EventInfo[] GetEvents(BindingFlags bindingAttr)
		{
			throw new NotSupportedException();
		}

		public sealed override FieldInfo GetField(string name, BindingFlags bindingAttr)
		{
			throw new NotSupportedException();
		}

		public sealed override FieldInfo[] GetFields(BindingFlags bindingAttr)
		{
			throw new NotSupportedException();
		}

		public sealed override Type GetInterface(string name, bool ignoreCase)
		{
			throw new NotSupportedException();
		}

		public sealed override Type[] GetInterfaces()
		{
			throw new NotSupportedException();
		}

		public sealed override MemberInfo[] GetMembers(BindingFlags bindingAttr)
		{
			throw new NotSupportedException();
		}

		protected abstract override MethodInfo GetMethodImpl(string name, BindingFlags bindingAttr, Binder binder, CallingConventions callConvention, Type[] types, ParameterModifier[] modifiers);

		public override MethodInfo[] GetMethods(BindingFlags bindingAttr)
		{
			throw new NotSupportedException();
		}

		public sealed override Type GetNestedType(string name, BindingFlags bindingAttr)
		{
			throw new NotSupportedException();
		}

		public override Type[] GetNestedTypes(BindingFlags bindingAttr)
		{
			throw new NotSupportedException();
		}

		public sealed override PropertyInfo[] GetProperties(BindingFlags bindingAttr)
		{
			throw new NotSupportedException();
		}

		protected sealed override PropertyInfo GetPropertyImpl(string name, BindingFlags bindingAttr, Binder binder, Type returnType, Type[] types, ParameterModifier[] modifiers)
		{
			throw new NotSupportedException();
		}

		protected abstract override bool HasElementTypeImpl();

		public sealed override object InvokeMember(string name, BindingFlags invokeAttr, Binder binder, object target, object[] args, ParameterModifier[] modifiers, System.Globalization.CultureInfo culture, string[] namedParameters)
		{
			throw new NotSupportedException();
		}

		protected abstract override bool IsArrayImpl();

		protected abstract override bool IsByRefImpl();

		protected sealed override bool IsCOMObjectImpl()
		{
			throw new NotSupportedException();
		}

		protected override bool IsPointerImpl()
		{
			return false;
		}

		protected sealed override bool IsPrimitiveImpl()
		{
			return false;
		}

#if NET_4_0
		public abstract override Module Module
		{
			get;
		}
#else
		public sealed override Module Module
		{
			get { throw new NotSupportedException(); }
		}
#endif

		public override Type UnderlyingSystemType
		{
			get { return this; }
		}

		public override Type DeclaringType
		{
			get { return null; }
		}

		public sealed override object[] GetCustomAttributes(Type attributeType, bool inherit)
		{
			throw new NotSupportedException();
		}

		public sealed override object[] GetCustomAttributes(bool inherit)
		{
			throw new NotSupportedException();
		}

		public sealed override bool IsDefined(Type attributeType, bool inherit)
		{
			throw new NotSupportedException();
		}

		public override string Name
		{
			get
			{
				string fullname = FullName;
				return fullname.Substring(fullname.LastIndexOf('.') + 1);
			}
		}

		public sealed override string Namespace
		{
			get
			{
				if (IsNested)
				{
					return null;
				}
				string fullname = FullName;
				int index = fullname.LastIndexOf('.');
				return index < 0 ? null : fullname.Substring(0, index);
			}
		}

		public override Type MakeArrayType()
		{
			return ArrayType.Make(this);
		}

		public override int MetadataToken
		{
			get { throw new NotImplementedException(); }
		}

		public override Type MakeByRefType()
		{
			return new ByRefType(this);
		}

		public override Type MakePointerType()
		{
			return new PointerType(this);
		}

		internal virtual int GetTypeToken()
		{
			return MetadataToken;
		}

		internal abstract ModuleBuilder ModuleBuilder { get; }

		// MONOBUG we need to override Equals because Mono's Type.Equals is broken
		public override bool Equals(object o)
		{
			Type other = o as Type;
			return other != null && ReferenceEquals(this.UnderlyingSystemType, other.UnderlyingSystemType);
		}

		// MONOBUG we need to override GetHashCode because Mono's Type.GetHashCode is broken
		public override int GetHashCode()
		{
			Type underlying = this.UnderlyingSystemType;
			if (ReferenceEquals(underlying, this))
			{
				return System.Runtime.CompilerServices.RuntimeHelpers.GetHashCode(this);
			}
			return underlying.GetHashCode();
		}

		// MONOBUG we need to override IsGenericTypeDefinition, because Mono's Type.IsGenericTypeDefinition will crash when called on a non-MonoType.
		public override bool IsGenericTypeDefinition
		{
			get { return false; }
		}
	}

	sealed class ByRefType : TypeBase
	{
		private readonly TypeBase type;

		internal ByRefType(TypeBase type)
		{
			this.type = type;
		}

		public override string AssemblyQualifiedName
		{
			get { throw new NotImplementedException(); }
		}

		public override Type BaseType
		{
			get { throw new NotImplementedException(); }
		}

		public override string FullName
		{
			get { throw new NotImplementedException(); }
		}

		public override Type GetElementType()
		{
			return type;
		}

		protected override TypeAttributes GetAttributeFlagsImpl()
		{
			throw new NotImplementedException();
		}

		protected override MethodInfo GetMethodImpl(string name, BindingFlags bindingAttr, Binder binder, CallingConventions callConvention, Type[] types, ParameterModifier[] modifiers)
		{
			throw new NotImplementedException();
		}

		protected override bool HasElementTypeImpl()
		{
			return true;
		}

		protected override bool IsArrayImpl()
		{
			return false;
		}

		protected override bool IsByRefImpl()
		{
			return true;
		}

		internal override ModuleBuilder ModuleBuilder
		{
			get { return type.ModuleBuilder; }
		}
	}

	sealed class PointerType : TypeBase
	{
		private readonly TypeBase type;

		internal PointerType(TypeBase type)
		{
			this.type = type;
		}

		public override string AssemblyQualifiedName
		{
			get { throw new NotImplementedException(); }
		}

		public override Type BaseType
		{
			get { throw new NotImplementedException(); }
		}

		public override string FullName
		{
			get { throw new NotImplementedException(); }
		}

		public override Type GetElementType()
		{
			return type;
		}

		protected override TypeAttributes GetAttributeFlagsImpl()
		{
			throw new NotImplementedException();
		}

		protected override MethodInfo GetMethodImpl(string name, BindingFlags bindingAttr, Binder binder, CallingConventions callConvention, Type[] types, ParameterModifier[] modifiers)
		{
			throw new NotImplementedException();
		}

		protected override bool HasElementTypeImpl()
		{
			return true;
		}

		protected override bool IsArrayImpl()
		{
			return false;
		}

		protected override bool IsByRefImpl()
		{
			return false;
		}

		protected override bool IsPointerImpl()
		{
			return true;
		}

		internal override ModuleBuilder ModuleBuilder
		{
			get { return type.ModuleBuilder; }
		}
	}
}
