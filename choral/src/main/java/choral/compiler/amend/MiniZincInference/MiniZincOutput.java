package choral.compiler.amend.MiniZincInference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import choral.ast.Name;
import choral.compiler.amend.Utils;
import choral.compiler.amend.MiniZincInference.MiniZincInput.Dependency;
import choral.exceptions.CommunicationInferenceException;
import choral.types.GroundInterface;
import choral.types.Member.HigherMethod;
import choral.types.World;
import choral.utils.Pair;

/**
 * A class to store all data generated from MiniZinc, used to create communications. 
 */
public class MiniZincOutput{
    Map<Integer, List<Dependency>> dataCommunications = new HashMap<>();
    Map<Dependency, Name> dependencyVariables = new HashMap<>();
    Map<Integer, List<MiniZincSelectionMethod>> selections = new HashMap<>();

    public void insertDataCom( Integer idx, Dependency dep ){
        dataCommunications.putIfAbsent(idx, new ArrayList<>());
        dataCommunications.get(idx).add(dep);
        dependencyVariables.put(dep, new Name( "dependencyAt" + dep.recipient() + "_" + Math.abs(dep.originalExpression().hashCode()) ));
    }

    public void insertSelection( 
        World recipient, 
        World sender, 
        List<Pair<String, GroundInterface>> channels,
        Integer idx
    ){
        Pair<Pair<String, GroundInterface>, HigherMethod> selectionMethod = 
            Utils.findSelectionMethod(recipient, sender, channels);
        if( selectionMethod == null )
            throw new CommunicationInferenceException("No viable selection method was found for " + recipient + " with sender " + sender);
        selections.putIfAbsent(idx, new ArrayList<>());
        selections.get(idx).add(
            new MiniZincSelectionMethod(
                selectionMethod.left().left(), 
                selectionMethod.left().right(), 
                selectionMethod.right(), 
                sender));
    }

}
