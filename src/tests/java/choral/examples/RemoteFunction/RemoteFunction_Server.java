package choral.examples.RemoteFunction;
import org.choral.channels.BiDataChannel_B;
import org.choral.annotations.Choreography;
import org.choral.lang.Unit;
import java.util.function.Function;

@Choreography( role = "Server", name = "RemoteFunction" )
class RemoteFunction_Server< T, R > {
	private BiDataChannel_B < T, R > ch;
	private Function < T, R > f;

	public RemoteFunction_Server( BiDataChannel_B < T, R > ch, Function < T, R > f ) {
		this.ch = ch;
		this.f = f;
	}

	public Unit call( Unit t ) {
		return call();
	}
	
	public Unit call() {
		return ch.< R >com( f.apply( ch.< T >com( Unit.id ) ) );
	}

}
