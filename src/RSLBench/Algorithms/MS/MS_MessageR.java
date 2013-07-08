package RSLBench.Algorithms.MS;

import RSLBench.Comm.Message;
import factorgraph.NodeFunction;
import factorgraph.NodeVariable;
import messages.MessageQ;
import messages.MessageR;



public class MS_MessageR extends MS_Message{

   
    private MessageR _messageR;
    
    public MS_MessageR(NodeFunction f, NodeVariable v, MessageR m){
        super(f,v,"R");
       // _function = f;
        //_varaible = v;
        _messageR = m;
      
    }
    

    
    public MessageR getMessage(){
        return _messageR;
    }
    
    @Override
    public int getBytes() {
        return getMessage().size() * Message.BYTES_UTILITY_VALUE 
                + super.getBytes();
    }

}