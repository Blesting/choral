package choral.compiler.amend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import choral.ast.CompilationUnit;
import choral.ast.Name;
import choral.ast.Position;
import choral.ast.body.Class;
import choral.ast.body.Enum;
import choral.ast.body.EnumConstant;
import choral.ast.body.VariableDeclaration;
import choral.ast.body.ClassMethodDefinition;
import choral.ast.body.ClassModifier;
import choral.ast.body.ConstructorDefinition;
import choral.ast.expression.*;
import choral.ast.statement.*;
import choral.ast.type.FormalWorldParameter;
import choral.ast.type.TypeExpression;
import choral.ast.type.WorldArgument;
import choral.ast.visitors.AbstractChoralVisitor;
import choral.types.GroundDataType;
import choral.types.GroundInterface;
import choral.types.Member.HigherMethod;
import choral.types.World;
import choral.utils.Pair;

/**
 * Knowledge of choice inference.
 * Iterates through a program and at every if-statement and switch-statement
 * sends a selection to every role but itself.
 * <p>
 * retruns a Selections object with all the selections to insert. the Data 
 * inference module needs to insert them.
 */
public class BasicKOCInference {

    Enum enumerator = null;
    Map< Statement, List<List<Expression>> > selections = new HashMap<>();
    Position position;
    
    public BasicKOCInference(){}
    
    public Selections inferKOC( CompilationUnit cu ){
        position = cu.position();
        for( Class cls : cu.classes() ){
            List< World > classWorlds = cls.worldParameters().stream().map( world -> (World)world.typeAnnotation().get() ).toList();

			for( ConstructorDefinition constructor : cls.constructors() ){
				new VisitStatement( classWorlds, constructor.signature().typeAnnotation().get().channels() ).visit(constructor.body());
			}
			
			for( ClassMethodDefinition method : cls.methods() ){
				if( method.body().isPresent() ){
					new VisitStatement( classWorlds, method.signature().typeAnnotation().get().channels() ).visit(method.body().get());
				}
			}
		}

        return new Selections(selections, enumerator);
    }


	private class VisitStatement extends AbstractChoralVisitor< Void >{
		
        List< World > allWorlds;
        List< Pair< String, GroundInterface > > methodChannels;

		public VisitStatement( List< World > allWorlds, List< Pair< String, GroundInterface > > channels ){
            this.allWorlds = allWorlds;
            this.methodChannels = channels;
        }

		@Override
		public Void visit( Statement n ) {
			return n.accept( this );
		}

		@Override
		public Void visit( ExpressionStatement n ) {
            return visitContinutation( n.continuation() );
		}

		@Override
		public Void visit( VariableDeclarationStatement n ) {
			return visitContinutation( n.continuation() );
		}

		@Override
		public Void visit( NilStatement n ) {
			return null;
		}

		@Override
		public Void visit( BlockStatement n ) {
			visit( n.enclosedStatement() ); 
            return visitContinutation( n.continuation() );
		}

		@Override
		public Void visit( IfStatement n ) {
			List< ? extends World > senders = ((GroundDataType)n.condition().typeAnnotation().get()).worldArguments();
            if( senders.size() != 1 ){
                System.out.println( "Found " + senders.size() + " roles, expected 1" );
                return null; // TODO throw some error
            }
            World sender = (World)senders.get(0);
            System.out.println( "Sender: " + sender );
            System.out.println( "AllWorlds: " + allWorlds );

            List<World> recipients = getParticipants(sender, List.of(n.ifBranch(), n.elseBranch()));
            if( recipients.size() > 0 ){
                Pair<List <Expression>, List<Expression>> selectionsPair = inferIfSelection( sender, recipients );
                List<Expression> ifSelections = selectionsPair.left();
                List<Expression> elseSelections = selectionsPair.right();
                selections.put( n, List.of( ifSelections, elseSelections ) );
            } 
            
            visitContinutation(n.ifBranch());
            visitContinutation(n.elseBranch());
            return visitContinutation( n.continuation() );
		}

		@Override // not supported
		public Void visit( SwitchStatement n ) {
			throw new UnsupportedOperationException("SwitchStatement not supported\n\tStatement at " + n.position().toString());
		}

		@Override
		public Void visit( TryCatchStatement n ) {
			visit( n.body() ); 
            n.catches(); // TODO this should be visited as well
            return visitContinutation( n.continuation() );
		}

		@Override
		public Void visit( ReturnStatement n ) {
			return visitContinutation( n.continuation() );
		}

		/** 
		 * Visits the continuation if there is one 
		 */
		private Void visitContinutation( Statement continutation ){
			return continutation == null ? null : visit(continutation);
		}

        /**
         * Creates selections for an if-statement
         */
        private Pair<List<Expression>, List<Expression>> inferIfSelection( World sender, List<World> recipients ){
            
            List<SelectionMethod> selectionList = findSelectionMethods( sender, recipients );

            List<Expression> ifSelections = new ArrayList<>();
            List<Expression> elseSelections = new ArrayList<>();
            for( SelectionMethod selectionMethod : selectionList ){
                Enum ifEnum = getEnum( 2 );
                // create selections for if branch
                ScopedExpression ifSelectionExpression = selectionMethod.createSelectionExpression( ifEnum, ifEnum.cases().get(0) );
                ifSelections.add( ifSelectionExpression );
                // create selections for else branch
                ScopedExpression elseSelectionExpression = selectionMethod.createSelectionExpression( ifEnum, ifEnum.cases().get(1) );
                elseSelections.add( elseSelectionExpression );
            }


            return new Pair<>(ifSelections, elseSelections);
        }

        private List<SelectionMethod> findSelectionMethods( World initialSender, List<World> recipientsList ){
            List<SelectionMethod> selectionList = new ArrayList<>();
            List<World> senders = new ArrayList<>();
            List<World> recipients = new ArrayList<>(recipientsList); // because I want a modifiable copy
            senders.add(initialSender);

            while( !recipients.isEmpty() && !senders.isEmpty() ){
                World sender = senders.remove(0); // the current sender to consider
                for( World recipient : recipients ){
                    // Tries to reach all recipients
                    SelectionMethod selectionMethod = findSelectionMethod(sender, recipient);
                    if( selectionMethod != null ){
                        // If a recipient is reachable, it becomes a new potential sender
                        senders.add(recipient);
                        selectionList.add( selectionMethod );
                    }
                }
                // Remove all the recipients that have already been reached
                recipients.removeAll(senders);
            }

            if( !recipients.isEmpty() ){
                for( World recipient : recipients ){
                    System.out.println( "No viable selection method was found for" + recipient );
                }
                return null; // TODO throw some error
            }

            return selectionList;
        }

        private SelectionMethod findSelectionMethod( World sender, World recipient ){
            for( Pair<String, GroundInterface> channelPair : methodChannels ){
            
                Optional<? extends HigherMethod> selectMethodOptional = 
                    channelPair.right().methods()
                        .filter( method ->
                            method.identifier().equals("select") && // it is a selection method (only checked through name)
                            method.innerCallable().signature().parameters().get(0).type().worldArguments().equals(List.of(sender)) && // its parameter's worlds are equal to our sender
                            method.innerCallable().returnType() instanceof GroundDataType && // probably redundant check, returntype should not be able to be void
                            ((GroundDataType)method.innerCallable().returnType()).worldArguments().get(0).equals(recipient) ) // its returntype's world is equal to our recipient
                        .findAny();
            
                if( selectMethodOptional.isPresent() ){
                    return new SelectionMethod( channelPair.left(), channelPair.right(), selectMethodOptional.get(), sender );
                }
            }
            // no viable selectionmethod was found
            return null;
        }

        /**
         * Returns an enumerator with the specified amount of cases. If no such 
         * enumerator exists, one is created.
         */
        private Enum getEnum( int numCases ){
            // Checks that an enumerator with enough cases has previously been created
            if( enumerator == null || enumerator.cases().size() < numCases ){
                // If not, creates one
                List<EnumConstant> cases = new ArrayList<>();
                for( int i = 0; i < numCases; i++ ){
                    cases.add( new EnumConstant(new Name( "CASE" + i ), Collections.emptyList(), null) );
                }
                
                enumerator = new Enum(
                    new Name( "KOCEnum" ), 
                    new FormalWorldParameter( new Name( "R" ) ), 
                    cases, 
                    Collections.emptyList(), 
                    EnumSet.noneOf( ClassModifier.class ), 
                    position);
            }
            return enumerator;
        }

        private List<World> getParticipants( World sender, List<Statement> statements ){
            Set<World> participants = new HashSet<>();
            for( Statement statement : statements ){
                participants.addAll( new GetStatementParticipants().getParticipants(statement) );
            }
            participants.remove(sender);

            return participants.stream().toList();
        }

	}

    private class SelectionMethod{
        private String channelIdentifier;
        private GroundInterface channel;
        private HigherMethod selectionMethod;
        private World sender;

        public SelectionMethod( 
            String channelIdentifier,
            GroundInterface channel,
            HigherMethod selectionMethod,
            World sender 
        ){
            this.channelIdentifier = channelIdentifier;
            this.channel = channel;
            this.selectionMethod = selectionMethod;
            this.sender = sender;
        }

        public String channelIdentifier(){
            return channelIdentifier;
        }

        public GroundInterface channel(){
            return channel;
        }

        public HigherMethod selectionMethod(){
            return selectionMethod;
        }

        public World sender(){
            return sender;
        }

        public ScopedExpression createSelectionExpression( Enum enumerator, EnumConstant enumCons ){
			
            TypeExpression typeExpression = new TypeExpression( 
                enumerator.name(), 
                Collections.emptyList(), // This needs to be "higher kinded" and can thus not have a worldargument
                Collections.emptyList(),
                position); // TODO proper position

            TypeExpression argScope = new TypeExpression(
                enumerator.name(), 
                List.of( new WorldArgument( new Name(sender.identifier() )) ), 
                Collections.emptyList(),
                position); // TODO proper position

            ScopedExpression argument = new ScopedExpression( // looks something like Enum@Sender.CHOICE
                new StaticAccessExpression( // Enum@Sender
                    argScope,
                    position), // TODO proper position
                new FieldAccessExpression( // CHOICE
                    enumCons.name(),
                    position), // TODO proper position
                position); // TODO proper position
            
			final List<Expression> arguments = List.of( argument );
			final Name name = new Name( selectionMethod.identifier() );
			final List<TypeExpression> typeArguments = List.of( typeExpression );
			
			MethodCallExpression scopedExpression = new MethodCallExpression(name, arguments, typeArguments, position); // TODO proper position
			
			// The comMethod is a method inside its channel, so we need to make the channel its scope
			FieldAccessExpression scope = new FieldAccessExpression(new Name(channelIdentifier), position); // TODO proper position
			
			// Something like channel.< Type >com( Expression )
			return new ScopedExpression(scope, scopedExpression, position);
        }

    }

    private class GetStatementParticipants extends AbstractChoralVisitor< Void >{
		
        Set< World > participants = new HashSet<>();

		public GetStatementParticipants(){}

        /** The main method of this class */
        public Set< World > getParticipants( Statement statement ){
            visit( statement );

            return participants;
        }

		@Override
		public Void visit( Statement n ) {
			return n.accept( this );
		}

		@Override
		public Void visit( ExpressionStatement n ) {
            Set<World> expressionParticipants = new GetExpressionParticipants().GetParticipants(n.expression());
            participants.addAll( expressionParticipants );
            
            return visitContinutation(n.continuation());
		}

		@Override
		public Void visit( VariableDeclarationStatement n ) {
			for( VariableDeclaration vd : n.variables() ){
                visitVariableDeclaration(vd);
            }
            return visitContinutation(n.continuation()); 
		}

		@Override
		public Void visit( NilStatement n ) {
			return visitContinutation(n.continuation()); 
		}

		@Override
		public Void visit( BlockStatement n ) {
			visit(n.enclosedStatement());
            
            return visitContinutation(n.continuation()); 
		}

		@Override
		public Void visit( IfStatement n ) {
            Set<World> conditionParticipants = new GetExpressionParticipants().GetParticipants(n.condition());
            participants.addAll(conditionParticipants);
            visit(n.ifBranch());
            visit(n.elseBranch());

			return visitContinutation(n.continuation()); 
		}

		@Override // TODO
		public Void visit( SwitchStatement n ) {
			// We do not check for participants inside nested switch statements. Switch statements will
            // be considered on their own.
			return visitContinutation(n.continuation()); 
		}

		@Override
		public Void visit( TryCatchStatement n ) {
			throw new UnsupportedOperationException("TryCatchStatement not supported\n\tStatement at " + n.position().toString());
		}

		@Override
		public Void visit( ReturnStatement n ) {
            Set<World> returnParticipants = new GetExpressionParticipants().GetParticipants(n.returnExpression());
            participants.addAll(returnParticipants);

			return visitContinutation(n.continuation());
		}

		/** 
		 * Visits the continuation if there is one 
		 */
		private Void visitContinutation( Statement continutation ){
			return continutation == null ? null : visit(continutation);
		}

        /**
		 * If there is no initializer, return the given {@code VaraibleDeclaration} without 
		 * change, otherwise visit its initializer and return a new {@code VaraibleDeclaration}
		 */
		private Void visitVariableDeclaration( VariableDeclaration vd ){
            // TODO look at vs's type's worldarguments
			
			if( !vd.initializer().isEmpty() ){
                Set<World> initializerParticipants = new GetExpressionParticipants().GetParticipants(vd.initializer().get());
                participants.addAll(initializerParticipants);
            }
            
            return null;
		}

	}

    private class GetExpressionParticipants extends AbstractChoralVisitor< Void >{
        
        private Set<World> participants = new HashSet<>();

        public GetExpressionParticipants(){}

        /** The main method of this class */
        public Set<World> GetParticipants( Expression expression ){
            
            visit(expression);
            return participants;
        }

		@Override
		public Void visit( Expression n ) {
			return n.accept( this );
		}

		@Override
		public Void visit( ScopedExpression n ) {
            // probably don't need to look at the ScopedExpression's worlds, since we will 
            // look at its scope and scopedExpression
			visit( n.scope() );
            return visit( n.scopedExpression() );
		}

		@Override
		public Void visit( FieldAccessExpression n ) {
			GroundDataType nType = (GroundDataType)n.typeAnnotation().get(); // assuming that a field cannot be void
            participants.addAll(nType.worldArguments());
            return null;
		}

		@Override
		public Void visit( MethodCallExpression n ) {
			
            if( !n.methodAnnotation().get().returnType().isVoid() ){
                GroundDataType returnType = (GroundDataType)n.methodAnnotation().get().returnType();
                participants.addAll(returnType.worldArguments());
            }

            for( Expression argument : n.arguments() ){
                visit(argument);
            }

            return null;
		}
		
		@Override
		public Void visit( AssignExpression n ) {
			visit(n.target());
            return visit(n.value());
		}

		@Override
		public Void visit( BinaryExpression n ) {
			visit(n.left());
            return visit(n.right());
		}

		@Override
		public Void visit( EnclosedExpression n ) {
			return visit(n.nestedExpression());
		}
		
		@Override
		public Void visit( StaticAccessExpression n ) {
			if( !n.typeAnnotation().get().isVoid() ){ // I think this might be able to be void
                GroundDataType staticAccessType = (GroundDataType)n.typeAnnotation().get();
                participants.addAll(staticAccessType.worldArguments());
            }
            return null;
		}

		@Override
		public Void visit( ClassInstantiationExpression n ) {
			// not sure how to get the class's worlds

            for( Expression argument : n.arguments() ){
                visit(argument);
            }

            return null;
		}

		@Override
		public Void visit( NotExpression n ) {
			return visit(n.expression());
		}

		@Override // not supported
		public Void visit( ThisExpression n ) {
			throw new UnsupportedOperationException("ThisExpression not supported\n\tExpression at " + n.position().toString());
		}

		@Override // not supported
		public Void visit( SuperExpression n ) {
			throw new UnsupportedOperationException("SuperExpression not supported\n\tExpression at " + n.position().toString());
		}

		@Override
		public Void visit( NullExpression n ) {
            return null;
		}

		public Void visit( LiteralExpression.BooleanLiteralExpression n ) {
            participants.add(n.world().typeAnnotation().get()); // TODO check worldargumetns for everything above this
			return null;
		}

		public Void visit( LiteralExpression.IntegerLiteralExpression n ) {
			participants.add(n.world().typeAnnotation().get());
			return null;
		}

		public Void visit( LiteralExpression.DoubleLiteralExpression n ) {
			participants.add(n.world().typeAnnotation().get());
			return null;
		}

		public Void visit( LiteralExpression.StringLiteralExpression n ) {
			participants.add(n.world().typeAnnotation().get());
			return null;
		}

		@Override // not supported
		public Void visit( TypeExpression n ) {
			throw new UnsupportedOperationException("TypeExpression not supported\n\tExpression at " + n.position().toString());
		}

		@Override // not supported
		public Void visit( BlankExpression n ){
			throw new UnsupportedOperationException("BlankExpression not supported\n\tExpression at " + n.position().toString());
		}

		@Override // not supported
		public Void visit( EnumCaseInstantiationExpression n ){
			throw new UnsupportedOperationException("EnumCaseInstantiationExpression not supported\n\tExpression at " + n.position().toString());
		}
    }
}
