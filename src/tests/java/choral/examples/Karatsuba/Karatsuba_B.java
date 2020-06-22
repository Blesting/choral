package choral.examples.Karatsuba;
import org.choral.lang.Unit;
import org.choral.channels.SymChannel_A;
import org.choral.channels.SymChannel_B;
import org.choral.annotations.Choreography;

@Choreography( role = "B", name = "Karatsuba" )
public class Karatsuba_B {
	public static Unit multiply( Unit n1, Unit n2, SymChannel_B < Object > ch_AB, SymChannel_A < Object > ch_BC, Unit ch_CA ) {
		return multiply( ch_AB, ch_BC );
	}
	
	public static Unit multiply( SymChannel_B < Object > ch_AB, SymChannel_A < Object > ch_BC ) {
		{
			switch( ch_AB.< Choice >select( Unit.id ) ){
				case DONE -> {
					return Unit.id;
				}
				default -> {
					throw new RuntimeException( "Received unexpected label from select operation" );
				}
				case RECUR -> {
					ch_AB.< Long >com( Karatsuba_A.multiply( ch_AB.< Long >com( Unit.id ), ch_AB.< Long >com( Unit.id ), ch_BC, Unit.id, ch_AB ) );
					Karatsuba_C.multiply( Unit.id, Unit.id, Unit.id, ch_AB, ch_BC );
					Karatsuba_B.multiply( Unit.id, Unit.id, ch_AB, ch_BC, Unit.id );
					return Unit.id;
				}
			}
		}
	}

}
