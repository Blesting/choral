package choral.compiler.amend;


import choral.ast.CompilationUnit;
import choral.ast.body.Class;
import choral.ast.body.ClassMethodDefinition;
import choral.ast.body.ConstructorDefinition;
import choral.ast.body.VariableDeclaration;
import choral.ast.body.Enum;
import choral.types.GroundClass;
import choral.types.GroundClassOrInterface;
import choral.types.GroundDataType;
import choral.types.GroundInterface;
import choral.types.GroundReferenceType;
import choral.types.GroundTypeParameter;
import choral.types.World;
import choral.types.Member.HigherCallable;
import choral.types.Member.HigherMethod;
import choral.utils.Pair;
import choral.ast.Name;
import choral.ast.type.*;
import choral.ast.visitors.AbstractChoralVisitor;
import choral.exceptions.CommunicationInferenceException;
import choral.ast.statement.*;
import choral.ast.expression.*;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Stream;

/**
 * A basic communication inference. Replace a dependency with a communication of that dependency.
 * <p>
 * For example, the code
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
 * Besides inferring data communications, this will also insert the provided selections.
 * <p>
 * This expects that there are no dependencies on literals and that the resulting {@code CompilationUnit} 
 * will be typed again, since most or all typeannotations will be lost
 */
public class BasicDataInference {

	/** An object containing all selections to be inserted */
	Selections selections;
	/** a map mapping from a statement to a list of all dependencies within that statement */
	Map<Statement, List<Dependency>> amendedStatements = new HashMap<>();

	public BasicDataInference( Selections selections ){
		this.selections = selections;
	}

	public CompilationUnit inferComms( CompilationUnit cu ){
		
		// make the map containing all dependencies
		buildAmendedStatements(cu);
		
		// Since everything in a CompilationUnit is final (in particular Statemetns and 
		// Expressions) we need to create a new CompilationUnit
		return createNewCompilationUnit( cu ); 
	}

	/**
	 * Iterates through all the methods of {@code cu} (including constructors), extracts 
	 * their dependencies, creates {@code Dependency} objects to store the dependencies 
	 * and collects them in {@code amendedStatements}.
	 */
	private void buildAmendedStatements( CompilationUnit cu ){
		for( HigherCallable method : getMethods(cu) ){
			for( Entry<World, List<Pair<Expression, Statement>>> entryset : method.worldDependencies().entrySet() ){
				
				World receiver = entryset.getKey();
				
				for( Pair<Expression, Statement> dependencyPair : entryset.getValue() ){
					Expression dependencyExpression = dependencyPair.left();
					Statement dependencyStatement = dependencyPair.right();

					Dependency newDependency = new Dependency(dependencyExpression);

					// Extract sender from dependency (what world needs to send data)
					World sender = getSender(dependencyExpression);
					
					System.out.println( "Role " + receiver + " needs " + dependencyExpression + " from role " + sender );
					
					// Find a viable communication method
					Pair<Pair<String, GroundInterface>, HigherMethod> comPair = findComMethod(
						receiver, 
						sender, 
						(GroundDataType)dependencyExpression.typeAnnotation().get(),
						method.channels());
					
					if( comPair == null ){
						// No viable communication method was found.
						throw new CommunicationInferenceException( "No viable communication method was found for the dependency " + dependencyExpression );
					}

					// This pair consists of the channel's identifier and its type.
					Pair<String, GroundInterface> comChannelPair = comPair.left();
					
					newDependency.setChannel( comChannelPair.left(), comChannelPair.right() );
					newDependency.setComMethod( comPair.right() );

					// Put the dependency in a list to be accessible later
					amendedStatements.putIfAbsent(dependencyStatement, new ArrayList<>());
					amendedStatements.get(dependencyStatement).add( newDependency );
				}
			}
			// clears the dependencies of the method so they won't accidentally considered twice
			// (probably not needed) 
			method.clearDependencies(); 
		}
	}

	/**
	 * Returns the world from a dependency expression that needs to send data. If the 
	 * dependency expression has more than one sender worldm an error is thrown.
	 * <p>
	 * If the dependency expression is a method call, the type-annotation is set to 
	 * be equal to the method annotation.
	 */
	private World getSender( Expression dependencyExpression ){
		// Extract senders from dependency (what world(s) needs to send data)
		List<? extends World> senders;
		if( dependencyExpression instanceof MethodCallExpression ){
			// MethodCallExpressions don't use typeAnnotation but instead use methodAnnotation
			GroundDataType methodReturnType = (GroundDataType)((MethodCallExpression)dependencyExpression).methodAnnotation().get().returnType();
			// Set typeannotation = returntype here for more easy access to an expression's type
			dependencyExpression.setTypeAnnotation(methodReturnType); 
			senders = methodReturnType.worldArguments();
		} else {
			senders = ((GroundDataType)dependencyExpression.typeAnnotation().get()).worldArguments();
		}
		
		if( senders.size() != 1 ){
			// We don't accept dependencies with multiple sender worlds
			throw new CommunicationInferenceException( "Found Dependency with " + senders.size() + " senders, expected 1" );
		}
		return senders.get(0);
	}

	/**
	 * Searches through the methods of {@code channels} and returns the first viable com 
	 * method based on the input. 
	 * <p>
	 * A viable method means:
	 * <ul>
	 * <li>{@code dependencyType} is a subtype of the channel's type</li>
	 * <li>The method identifer is "com"</li>
	 * <li>It rakes a parameter at world {@code sender}</li>
	 * <li>It returns something at world {@code recipient}</li>
	 * </ul>
	 * Returns null if no such method is found.
	 */
	private Pair<Pair<String, GroundInterface>, HigherMethod> findComMethod(
		World recepient, 
		World sender, 
		GroundDataType dependencyType, 
		List<Pair<String, GroundInterface>> channels){
		
		for( Pair<String, GroundInterface> channelPair : channels ){

			// Data channels might not return the same datatype at the receiver as 
			// the datatype from the sender. Since we only store one type for the 
			// dependency we assume that all types in a channel are the same.
			GroundInterface channel = channelPair.right();
			if( channel.typeArguments().stream().anyMatch( typeArg -> dependencyType.typeConstructor().isSubtypeOf( typeArg ) ) ){
				
				Optional<? extends HigherMethod> comMethodOptional = 
					channelPair.right().methods()
						.filter( method ->
							method.identifier().equals("com") && // it is a com method (only checked through name)
							method.innerCallable().signature().parameters().get(0).type().worldArguments().equals(List.of(sender)) && // its parameter's worlds are equal to our dependency's world(s)
							method.innerCallable().returnType() instanceof GroundDataType && // probably redundant check, returntype should not be able to be void
							((GroundDataType)method.innerCallable().returnType()).worldArguments().get(0).equals(recepient) ) // its returntype's world is equal to our dependency recipient
						.findAny();
				
				if( comMethodOptional.isPresent() ){
					return new Pair<>( channelPair, comMethodOptional.get());
				}
			}
		}
		return null;
	}

	/**
	 * Retreives all methods from the {@code CompilationUnit} including constructors
	 */
	private List<HigherCallable> getMethods( CompilationUnit cu ){
		return Stream.concat( 
			cu.classes().stream()
				.flatMap( cls -> cls.methods().stream() )
				.map( method -> method.signature().typeAnnotation().get() ), // we assume that methods are type-annotated
			cu.classes().stream()
				.flatMap(cls -> cls.constructors().stream()
				.map( method -> method.signature().typeAnnotation().get() )
				)).toList();
	}

	/**
	 * Creates a new {@code CompilationUnit} from the old, with amended method bodies (changed to 
	 * include communications) 
	 * <p>
	 * We need to create a new {@code CompilationUnit} since everything in a {@code CompilationUnit} 
	 * (in particular {@code Statements} and {@code Expressions}) are final, and can therefore not 
	 * be modified.
	 */
	private CompilationUnit createNewCompilationUnit( CompilationUnit old ){
		List<Class> newClasses = new ArrayList<>();
		for( Class cls : old.classes() ){
			List<ConstructorDefinition> newConstructors = new ArrayList<>();
			for( ConstructorDefinition constructor : cls.constructors() ){
				Statement newBody = new VisitStatement().visit(constructor.body());

				newConstructors.add(new ConstructorDefinition(
					constructor.signature(), 
					constructor.explicitConstructorInvocation().orElse( null ),
					newBody, 
					constructor.annotations(), 
					constructor.modifiers(), 
					constructor.position()));
			}
			
			List<ClassMethodDefinition> newMethods = new ArrayList<>();
			for( ClassMethodDefinition method : cls.methods() ){
				Statement newBody = null;
				if( method.body().isPresent() ){
					newBody = new VisitStatement().visit(method.body().get());
				}

				newMethods.add(new ClassMethodDefinition(
					method.signature(), 
					newBody, 
					method.annotations(), 
					method.modifiers(), 
					method.position()));
			}

			newClasses.add(new Class(
				cls.name(), 
				cls.worldParameters(), 
				cls.typeParameters(), 
				cls.extendsClass(), 
				cls.implementsInterfaces(), 
				cls.fields(), 
				newMethods, 
				newConstructors, 
				cls.annotations(), 
				cls.modifiers(), 
				cls.position()));
		}

		List<Enum> newEnums = old.enums();
		if( selections.enumerator() != null ) 
			newEnums.add( selections.enumerator() ); // insert enum created by KOC inference 

		return new CompilationUnit(
			old.packageDeclaration(), 
			old.imports(), 
			old.interfaces(), 
			newClasses, 
			newEnums, 
			old.position().sourceFile());
	}

	/**
	 * Amends {@code Statements}.
	 * <p>
	 * Iterates through {@code Statement}s and their continuations, and anytime a {@code 
	 * Statement} is in the {@code amendedStatements} map, the {@code Expression}s of that 
	 * {@code Statement} are visited by {@code VisitExpression}
	 */
	private class VisitStatement extends AbstractChoralVisitor< Statement >{
		
		
		public VisitStatement(){}

		@Override
		public Statement visit( Statement n ) {
			return n.accept( this );
		}

		@Override
		public Statement visit( ExpressionStatement n ) {
			List<Dependency> dependencyList = amendedStatements.get(n);
			Expression newExpression;
			if( dependencyList == null ){
				// If this statement has no dependencies, there is no reason to visit its expression
				newExpression = n.expression();
			} else{
				newExpression = visitExpression(dependencyList, n.expression());
			}

			return new ExpressionStatement(
				newExpression, 
				visitContinutation(n.continuation()), 
				n.position());
		}

		@Override
		public Statement visit( VariableDeclarationStatement n ) {
			List<Dependency> dependencyList = amendedStatements.get(n);
			List<VariableDeclaration> newVariables = new ArrayList<>();
			if( dependencyList == null ){
				// If this statement has no dependencies, there is no reason to visit its expressions (n.variables())
				newVariables = n.variables();
			} else{
				for( VariableDeclaration x : n.variables() ) {
					// If there are dependencies, we visit each VariableDeclaration seperately 
					newVariables.add( visitVariableDeclaration( dependencyList, x ) );
				}
			}

			return new VariableDeclarationStatement(
				newVariables, 
				visitContinutation(n.continuation()), 
				n.position());
		}

		@Override
		public Statement visit( NilStatement n ) {
			return new NilStatement(n.position());
		}

		@Override
		public Statement visit( BlockStatement n ) {
			// BlockStatements don't directly contain any expressions (they 
			// contain Statemetns that then might contain Expressions) and 
			// thus do not need to be checked for dependencies 
			return new BlockStatement(
				n.enclosedStatement(), 
				visitContinutation(n.continuation()), 
				n.position());
		}

		@Override
		public Statement visit( IfStatement n ) {
			List<Dependency> dependencyList = amendedStatements.get(n);
			Expression newCondition;
			if( dependencyList == null ){
				// If this statement has no dependencies, there is no reason to visit its condition
				newCondition = n.condition();
			} else{
				newCondition = visitExpression(dependencyList, n.condition());
			}

			// Insert selections if there are any
			Statement newIfBranch = visitContinutation(n.ifBranch());
			Statement newElseBranch = visitContinutation(n.elseBranch());
			List<List<Expression>> selectionsToInsert = selections.selections().get( n );
			if( selectionsToInsert != null ){
				newIfBranch = Selections.chainSelections( newIfBranch , selectionsToInsert.get(0));
				newElseBranch = Selections.chainSelections( newElseBranch , selectionsToInsert.get(1));
			}

			return new IfStatement(
				newCondition, 
				newIfBranch,
				newElseBranch, 
				visitContinutation(n.continuation()), n.position());
		}

		@Override // not supported
		public Statement visit( SwitchStatement n ) {
			throw new UnsupportedOperationException("SwitchStatement not supported\n\tStatement at " + n.position().toString());
		}

		@Override
		public Statement visit( TryCatchStatement n ) {
			List<Dependency> dependencyList = amendedStatements.get(n);
			List< Pair< VariableDeclaration, Statement > > newCatches = new ArrayList<>();
			if( dependencyList == null ){
				// If this statement has no dependencies, there is no reason to visit its expressions, 
				// but the statements still need to be visited
				for( Pair< VariableDeclaration, Statement > pair : n.catches() ){
					newCatches.add( 
						new Pair<>( 
							pair.left(), 
							visit(pair.right()) ) );
				}
			} else {
				for( Pair< VariableDeclaration, Statement > pair : n.catches() ){
					newCatches.add( 
						new Pair<>( 
							visitVariableDeclaration( dependencyList, pair.left() ), 
							visit(pair.right()) ) );
				}
			}
			
			return new TryCatchStatement(
				visit(n.body()), 
				newCatches, 
				visitContinutation(n.continuation()), 
				n.position());
		}

		@Override
		public Statement visit( ReturnStatement n ) {
			List<Dependency> dependencyList = amendedStatements.get(n);
			Expression newReturnExpression;
			if( dependencyList == null ){
				// If this statement has no dependencies, there is no reason to visit its expression
				newReturnExpression = n.returnExpression();
			} else{
				newReturnExpression = visitExpression(dependencyList, n.returnExpression());
			}

			return new ReturnStatement(
				newReturnExpression, 
				visitContinutation(n.continuation()), 
				n.position());
		}

		/** 
		 * Visits the continuation if there is one 
		 */
		private Statement visitContinutation( Statement continutation ){
			return continutation == null ? null : visit(continutation);
		}

		/**
		 * For some initial {@code Expression} and a list of {@code Dependency}s, visits the 
		 * initial {@code Expression} with the dependency list.
		 * @param dependencyList must not be empty
		 */
		private Expression visitExpression( List<Dependency> dependencyList, Expression first ){
			
			Expression newExpression = new VisitExpression(dependencyList).visit(first);

			if( !dependencyList.isEmpty() ){ 
				System.out.println( "ERROR! Could not resole the following dependencies" );
				for( Dependency dependency  : dependencyList ){
					System.out.println( "\t" + dependency.originalExpression() );
				}
			}

			return newExpression;
		}

		/**
		 * If there is no initializer, return the given {@code VaraibleDeclaration} without 
		 * change, otherwise visit its initializer and return a new {@code VaraibleDeclaration}
		 */
		private VariableDeclaration visitVariableDeclaration( List<Dependency> dependencyList, VariableDeclaration vd ){
			if( vd.initializer().isEmpty() )
				return vd;
			
			return new VariableDeclaration(
				vd.name(), 
				vd.type(), 
				vd.annotations(), 
				(AssignExpression)visitExpression(dependencyList, vd.initializer().get()), 
				vd.position());
		}

	}

	/**
	 * Amends {@code Expressions}.
	 * <p>
	 * Iterates through {@code Expression}s and checks if they are equal to 
	 * anything inside {@code dependencyList}. If an {@code Expression} is 
	 * equal to a dependency it is replaced with a communication.
	 */
	private class VisitExpression extends AbstractChoralVisitor< Expression >{
		// We should be able to return immediately when the dependencylist becomes empty
		// Maybe its enough to insert a check in checkIfDependency?
		// TODO implement this

		/** A list of all the dependencies to check in this expression */
		List<Dependency> dependencyList;

		public VisitExpression(List<Dependency> dependencyList){
			this.dependencyList = dependencyList;
		}

		@Override
		public Expression visit( Expression n ) {
			return n.accept( this );
		}

		@Override
		public Expression visit( ScopedExpression n ) {
			Expression dependencyCheck = checkIfDependency(n);
			if( dependencyCheck != null ) {
				return dependencyCheck;
			}
			
			Expression newscopedExpression = visit(n.scopedExpression());

			return new ScopedExpression(
				n.scope(), // Dependencies cannot be in the scope, so no need to check
				newscopedExpression, 
				n.position());
		}

		@Override
		public Expression visit( FieldAccessExpression n ) {
			Expression dependencyCheck = checkIfDependency(n);
			if( dependencyCheck != null ) {
				return dependencyCheck;
			}
			return n;
		}

		@Override
		public Expression visit( MethodCallExpression n ) {
			Expression dependencyCheck = checkIfDependency(n);
			if( dependencyCheck != null ) {
				return dependencyCheck;
			}

			List<Expression> newArgs = new ArrayList<>();
			for(int i = 0; i < n.arguments().size(); i++){
				Expression argument = n.arguments().get(i);
				
				// visit all of the arguments and add them to the new list of arguments
				newArgs.add( visit(argument) );
			}

			return new MethodCallExpression(
				n.name(), 
				newArgs, 
				n.typeArguments(), 
				n.position());
		}
		
		@Override
		public Expression visit( AssignExpression n ) {
			// an assignExpression cannot itself be a dependency, but its value() might

			Expression newValue = visit( n.value() );

			return new AssignExpression(
				newValue, 
				n.target(), 
				n.operator(),
				n.position());
		}

		@Override
		public Expression visit( BinaryExpression n ) {
			Expression dependencyCheck = checkIfDependency(n);
			if( dependencyCheck != null ) {
				return dependencyCheck;
			}
			
			Expression newLeft = visit( n.left() );
			Expression newRight = visit( n.right() );
			
			return new BinaryExpression(
				newLeft, 
				newRight, 
				n.operator(), 
				n.position());
		}

		@Override
		public Expression visit( EnclosedExpression n ) {
			Expression dependencyCheck = checkIfDependency(n);
			if( dependencyCheck != null ) {
				return dependencyCheck;
			}

			Expression newNestedExpression = visit( n.nestedExpression() );
			
			return new EnclosedExpression(
				newNestedExpression, 
				n.position());
		}
		
		@Override
		public Expression visit( StaticAccessExpression n ) {
			Expression dependencyCheck = checkIfDependency(n);
			if( dependencyCheck != null ) {
				return dependencyCheck;
			}
			return n;
		}

		@Override
		public Expression visit( ClassInstantiationExpression n ) {
			Expression dependencyCheck = checkIfDependency(n);
			if( dependencyCheck != null ) {
				return dependencyCheck;
			}

			List<Expression> newArgs = new ArrayList<>();
			for(int i = 0; i < n.arguments().size(); i++){
				Expression argument = n.arguments().get(i);
				
				// visit all of the arguments and add them to the new list of arguments
				newArgs.add( visit(argument) );
			}

			return new ClassInstantiationExpression(
				n.typeExpression(), 
				newArgs, 
				n.typeArguments(), 
				n.position());
		}

		@Override
		public Expression visit( NotExpression n ) {
			Expression dependencyCheck = checkIfDependency(n);
			if( dependencyCheck != null ) {
				return dependencyCheck;
			}

			Expression newExpression = visit( n.expression() );
			
			return new NotExpression(
				newExpression, 
				n.position());
		}

		@Override // not supported
		public Expression visit( ThisExpression n ) {
			throw new UnsupportedOperationException("ThisExpression not supported\n\tExpression at " + n.position().toString());
		}

		@Override // not supported
		public Expression visit( SuperExpression n ) {
			throw new UnsupportedOperationException("SuperExpression not supported\n\tExpression at " + n.position().toString());
		}

		@Override
		public Expression visit( NullExpression n ) {
			// Nothing can depend on null, so no need to check dependencies
			return n;
		}

		public Expression visit( LiteralExpression.BooleanLiteralExpression n ) {
			// literals are not permited to be in dependencies
			return n;
		}

		public Expression visit( LiteralExpression.IntegerLiteralExpression n ) {
			// literals are not permited to be in dependencies
			return n;
		}

		public Expression visit( LiteralExpression.DoubleLiteralExpression n ) {
			// literals are not permited to be in dependencies
			return n;
		}

		public Expression visit( LiteralExpression.StringLiteralExpression n ) {
			// literals are not permited to be in dependencies
			return n;
		}

		@Override // not supported
		public Expression visit( TypeExpression n ) {
			throw new UnsupportedOperationException("TypeExpression not supported\n\tExpression at " + n.position().toString());
		}

		@Override // not supported
		public Expression visit( BlankExpression n ){
			throw new UnsupportedOperationException("BlankExpression not supported\n\tExpression at " + n.position().toString());
		}

		@Override // not supported
		public Expression visit( EnumCaseInstantiationExpression n ){
			throw new UnsupportedOperationException("EnumCaseInstantiationExpression not supported\n\tExpression at " + n.position().toString());
		}

		/**
		 * Checks if an {@code Expression} is a {@code Dependency}. If so, visits the {@code 
		 * Expression} and returns the visited expression wrapped in a communication, specified 
		 * by the {@code Dependency}. Otherwise returns null.
		 */
		private Expression checkIfDependency( Expression n ){
			for( Dependency dependency : dependencyList ){
				if( n.equals(dependency.originalExpression) ){
					dependencyList.remove(dependency);
					return dependency.createComExpression(visit(n));
				}
			}
			return null;
		}
	}

	/**
	 * A class for represent a dependency, consisting of the original dependency expression
	 * and a communication (both channel and method) for satisfying this dependency.
	 * <p>
	 * Has method {@code createComExpression} for creating a communication expression out of
	 * the stored communication and the provided {@code Expression} argument.
	 */
	private class Dependency {
		private Expression originalExpression;
		private HigherMethod comMethod;
		private String channelIdentifier;
		private GroundInterface channel;

		public Dependency( Expression originalExpression ){
			this.originalExpression = originalExpression;
		}

		public Expression originalExpression(){
			return originalExpression;
		}

		public HigherMethod comMethod(){
			return comMethod;
		}

		public String channelIdentifier(){
			return channelIdentifier;
		}

		public GroundInterface channel(){
			return channel;
		}

		public void setChannel( GroundInterface channel ){
			this.channel = channel;
		}

		public void setChannel( String channelIdentifier, GroundInterface channel ){
			this.channelIdentifier = channelIdentifier;
			this.channel = channel;
		}

		public void setComMethod( HigherMethod comMethod ){
			this.comMethod = comMethod;
		}

		public void setChannelIdentifier( String channelIdentifier ){
			this.channelIdentifier = channelIdentifier;
		}

		/**
		 * Creates the {@code Expression} containing the communiction of the dependency. Note
		 * that the argument of the communication method ({@code visitedExpression}) must 
		 * be visited before creating the comExpression.
		 * <p>
		 * This expression needs
		 * <p>
		 * 1. A name
		 * 		- The name of out communication method (com)
		 * <p>
		 * 2. Arguments 
		 * 		- Our {@code visitedDependency} expression. This is expected to be a 
		 * 		visited version of {@code originalExpression}. Note that this must be 
		 * 		visited before calling {@code createComExpression}. This is because 
		 * 		we use java's {@code Object.equals()} to check if  an expression is a 
		 * 		dependency. If {@code createComExpression} is called before visiting 
		 * 		{@code originalExpression} then dependencies inside
		 * 		{@code originalExpression} (nested dependencies) will not be caught. 
		 * <p>
		 * 3. type argumetns
		 * 		- com methods always need the type of the data they are communicating. 
		 * 		This is stored as a {@code TypeExpression}. These TypeExpressions contain
		 * 		the unqualified name of the type (e.g. not "java.lang.Object", only 
		 * 		"Object") and composite types (types containing other types (like 
		 * 		{@code List})) also have a list of {@code TypeExpression}s representing 
		 * 		its inner types.
		 * @param visitedDependency - Must be visited before calling this method, 
		 * 		otherwise nested dependencies will not be caught.
		 */
		public Expression createComExpression( Expression visitedDependency ){

			TypeExpression typeExpression;
			if( originalExpression.typeAnnotation().get() instanceof GroundTypeParameter ){
				typeExpression = getTypeExpression((GroundTypeParameter)originalExpression.typeAnnotation().get());
			} else {
				typeExpression = getTypeExpression((GroundClassOrInterface)originalExpression.typeAnnotation().get());
			}
			
			final List<Expression> arguments = List.of( visitedDependency );
			final Name name = new Name(comMethod.identifier());
			final List<TypeExpression> typeArguments = List.of( typeExpression );
			
			MethodCallExpression scopedExpression = new MethodCallExpression(name, arguments, typeArguments, visitedDependency.position());
			
			// The comMethod is a method inside a channel, so we need to make the channel its scope
			FieldAccessExpression scope = new FieldAccessExpression(new Name(channelIdentifier), visitedDependency.position());
			
			// Something like channel.< Type >com( visitedDependency )
			return new ScopedExpression(scope, scopedExpression);
		}

		private TypeExpression getTypeExpression( GroundClassOrInterface type ){
			return new TypeExpression(
				new Name(type.typeConstructor().identifier()),
				Collections.emptyList(), 
				type.typeArguments().stream().map( typeArg -> getTypeExpression(typeArg.applyTo(type.worldArguments())) ).toList());
		}

		private TypeExpression getTypeExpression( GroundReferenceType type ){
			if( type instanceof GroundClass ){ // I think this is only not true for primitive types, which cannot be communicated
				GroundClass typeGC = (GroundClass)type;
				return new TypeExpression(
					new Name(typeGC.typeConstructor().identifier()),
					Collections.emptyList(), 
					typeGC.typeArguments().stream().map( typeArg -> getTypeExpression(typeArg.applyTo(type.worldArguments())) ).toList());
			}
			if( type instanceof GroundTypeParameter ){
				GroundTypeParameter typeGTP = (GroundTypeParameter)type;
				return new TypeExpression(
					new Name(typeGTP.typeConstructor().identifier()),
					Collections.emptyList(), 
					Collections.emptyList());
			}
			
			throw new CommunicationInferenceException( "ERROR! Not a GroundClass or GroundTypeParameter. Found " + type.getClass() ); 
		}

	}
}