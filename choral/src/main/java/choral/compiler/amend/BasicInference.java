package choral.compiler.amend;


import choral.ast.CompilationUnit;
import choral.ast.body.Class;
import choral.ast.body.ClassMethodDefinition;
import choral.types.GroundDataType;
import choral.types.GroundInterface;
import choral.types.World;
import choral.types.Member.HigherMethod;
import choral.ast.expression.Expression;
import choral.ast.expression.MethodCallExpression;
import choral.ast.Name;
import choral.ast.type.TypeExpression;

import java.util.*;
import java.util.Map.Entry;

/**
 * A basic communication inference. Replace a dependency with a communication of that dependency.
 * <p>
 * for example
 * <pre>
 * {@code
 * SymChannel@( A, B )<String> channel;
 *String@B b = "var_b"@B;
 *String@A a = "var_a,"@A + b;
 * }
 * </pre>
 * Would become 
 * <pre>
 * {@code
 * SymChannel@( A, B )<String> channel;
 *String@B b = "var_b"@B;
 *String@A a = "var_a,"@A + ch.<String>com( b );
 * }
 * </pre>
 */
public class BasicInference {
	/*
	 * Iterate through all dependencies
	 * Find a channel that can be used
	 * if not channel, throw error
	 * replace the problematic expression with a communication of that expression
	 */
	// TODO cleanup. This is a mess.
	public static void inferComms( CompilationUnit cu ){
		for( Class cls : cu.classes() ){
			for( ClassMethodDefinition method : cls.methods() ){
				HigherMethod higherMethod = method.signature().typeAnnotation().get();
				for( Entry<World, List<Expression>> entryset : higherMethod.worldDependencies().entrySet() ){
					
					World receiver = entryset.getKey();
					
					for( Expression dependency : entryset.getValue() ){
						// TODO probably assert that sender can only be a single world
						List<? extends World> sender = ((GroundDataType)dependency.typeAnnotation().get()).worldArguments();
						GroundDataType dependencyType = ((GroundDataType)dependency.typeAnnotation().get()); // 99% certain this cannot be void, maybe add an assert?
						
						System.out.println( "Role " + receiver + " needs " + dependency + " of type " + dependencyType + " from role " + sender );
						
						HigherMethod comMethod = findComMethod(receiver, (World)sender.get(0), higherMethod.channels());
						if( comMethod == null ){
							// TODO throw some exception
							return;
						}

						Expression newExpression = createComExpression(dependency, comMethod);
						
						// TODO insert newExpression into the ast

						for( GroundInterface channel : higherMethod.channels() ){
							if( channel.typeArguments().size() == 1 ){ 		// checks that this channel is not a purely selection chanel (only
																			// data channels have a type argument)
								System.out.println( "Potential channel: " + channel );
								System.out.println( "type argument check: " + channel.typeArguments().get(0).isSubtypeOf_relaxed(dependencyType) ); 
								// TODO find proper way to check if channel dependencyType is a subtype of channel.typeArguments 
								
							}
							
						}
					}
				}
			}

		}
	}

	private static HigherMethod findComMethod(World recepient, World sender, List<GroundInterface> channels){
		for( GroundInterface channel : channels ){
			Optional<? extends HigherMethod> comMethodOptional = 
				channel.methods()
					.filter( method ->
						method.identifier().equals("com") && // it is a com method (only checked through name)
						method.innerCallable().signature().parameters().get(0).type().worldArguments().equals(List.of(sender)) && // its parameter's worlds are equal to our dependency's world(s)
						method.innerCallable().returnType() instanceof GroundDataType && // probably redundant check, returntype should not be able to be void
						((GroundDataType)method.innerCallable().returnType()).worldArguments().get(0).equals(recepient) ) // its returntype's world is equal to our dependency recipient
					.findAny();
			
			if( comMethodOptional.isPresent() ){
				return comMethodOptional.get();
			}
		}
		return null;
	}

	private static Expression createComExpression(Expression dependency, HigherMethod comMethod){
		/*
		* To make an expression we need
		* 	1. a name
		* 		(the name of out communication method (com))
		* 	2. argumetns
		* 		(our dependency expression)
		* 	3. type argumetns
		* 		com methods always need the type of the data they are communicating. 
		* 		this is stored as a type expression. from looking at how choral treats 
		* 		com methods in the examples, these type expressions onle have a name (and 
		* 		NOTHING else). also this name is only the simple name for the type? e.g. 
		* 		not "java.lang.Object", only "Object". so this is the unqualified(?) name 
		* 		for the type.
		*/
		GroundDataType dependencyType = ((GroundDataType)dependency.typeAnnotation().get()); // 99% certain this cannot be void, maybe add an assert?
		final List<Expression> arguments = List.of(dependency);
		final Name name = new Name(comMethod.identifier());
		final List<TypeExpression> typeArguments = List.of(new TypeExpression(new Name(dependencyType.typeConstructor().toString()), null, null));
		// TODO how do I get the type's identifier without relying on toString?
		MethodCallExpression newExpression = new MethodCallExpression(name, arguments, typeArguments);
		System.out.println( "newExpression: " + newExpression );
		System.out.println( "MethodCallExpression: " + newExpression.toString() );
		System.out.println( "typearguments: " + newExpression.typeArguments().get(0).name() );
		System.out.println( "typearguments: " + newExpression.typeArguments().get(0).typeArguments() );
		System.out.println( "typearguments: " + newExpression.typeArguments().get(0).worldArguments() );
		
		return newExpression;
	}

}
