/**
 * Copyright (c) 2017-2018 The Elastos Developers
 * <p>
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.elastos.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.util.*;

import com.alibaba.fastjson.JSON;
import net.sf.json.JSONObject;
import org.elastos.api.Basic;
import org.elastos.api.SingleSignTransaction;
import org.elastos.conf.BasicConfiguration;
import org.elastos.conf.DidConfiguration;
import org.elastos.conf.NodeConfiguration;
import org.elastos.conf.RetCodeConfiguration;
import org.elastos.ela.ECKey;
import org.elastos.ela.Ela;
import org.elastos.ela.SignTool;
import org.elastos.ela.Util;
import org.elastos.elaweb.ElaController;
import org.elastos.entity.*;
import org.elastos.exception.ApiInternalException;
import org.elastos.exception.ApiRequestDataException;
import org.elastos.util.*;
import org.elastos.util.ela.ElaKit;
import org.elastos.util.ela.ElaSignTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.xml.bind.DatatypeConverter;

/**
 * @author clark
 * <p>
 * Apr 21, 2018 12:45:54 PM
 */
@Service
public class ElaService {

    private static final String CHARSET = "UTF-8";

    @Autowired
    private NodeConfiguration nodeConfiguration;
    @Autowired
    private BasicConfiguration basicConfiguration;
    @Autowired
    private RetCodeConfiguration retCodeConfiguration;
    @Autowired
    private DidConfiguration didConfiguration;

    private static Logger logger = LoggerFactory.getLogger(ElaService.class);

    /**
     * create a ela wallet
     * @return
     */
    public String createWallet(){
        JSONObject result = new JSONObject();
        String privateKey = Ela.getPrivateKey();
        String publicKey  = Ela.getPublicFromPrivate(privateKey);
        String publicAddr = Ela.getAddressFromPrivate(privateKey);
        result.put("privateKey",privateKey);
        result.put("publicKey",publicKey);
        result.put("address",publicAddr);
        return JSON.toJSONString(new ReturnMsgEntity().setResult(result).setStatus(retCodeConfiguration.SUCC()));
    }

    public String sendRawTx(RawTxEntity rawTxEntity){
        String rawTx = JSON.toJSONString(rawTxEntity);
        ReturnMsgEntity.ELAReturnMsg msg = JsonUtil.jsonStr2Entity(HttpKit.post(nodeConfiguration.sendRawTransaction(),rawTx),ReturnMsgEntity.ELAReturnMsg.class);
        long status = 0;
        if(msg.getError() == 0){
            status = retCodeConfiguration.SUCC();
        }else{
            status = retCodeConfiguration.PROCESS_ERROR();
        }
        return JSON.toJSONString(new ReturnMsgEntity().setResult(msg.getResult()).setStatus(status));
    }

    /**
     * genHdTx info
     * @param hdTxEntity info entity
     * @return
     * @throws Exception
     */
    public String genHdTx(HdTxEntity hdTxEntity) throws Exception {

        String[] inputAddrs = hdTxEntity.getInputs();
        List<List<Map>> utxoList = new ArrayList<>();
        for (int i = 0; i < inputAddrs.length; i++) {

            String utxoStr = getUtxoByAddr(inputAddrs[i]);

            List<Map> utxo = stripUtxo(utxoStr);

            if(utxo != null){
                utxoList.add(utxo);
            }

        }

        return JSON.toJSONString(new ReturnMsgEntity().setResult(genHdTx(hdTxEntity, utxoList)).setStatus(retCodeConfiguration.SUCC()));

    }

    /**
     * get the current height of blockchain
     * @return
     */
    public String getCurrentHeight(){

        return reqChainData(nodeConfiguration.getBlockHeight());
    }

    /**
     * get block txs by height
     * @return
     */
    public String getBlockTxsByHeight(String height){

        return reqChainData(nodeConfiguration.getBlockTxByHeight()+ height);
    }

    /**
     * get block by height
     * @param height
     * @return
     */
    public String getBlockByHeight(String height){

        return reqChainData(nodeConfiguration.getBlockByHeight()+ height);
    }

    /**
     * get block by hash
     * @param hash
     * @return
     */
    public String getBlockByHash(String hash){

        return reqChainData(nodeConfiguration.getBlockByhash()+ hash);
    }

    /**
     * get transaction by hash
     * @param hash
     * @return
     */
    public String getTransactionByHash(String hash){

        return reqChainData(nodeConfiguration.getTransaction()+ hash);
    }

    /**
     * get address balance
     * @param address
     * @return
     */
    public String getBalance(String address){

        String result = HttpKit.get(nodeConfiguration.getUtxoByAddr()+ address);

        Map<String,Object>  resultMap = (Map<String,Object>) JSON.parse(result);

        Object resObj = resultMap.get("Result");

        if (resObj == null || StrKit.isBlank(resObj+"") || (resObj +"").equalsIgnoreCase("null")){

            return JSON.toJSONString(new ReturnMsgEntity().setResult("0.0").setStatus(retCodeConfiguration.SUCC()));

        }

        Map m = ((List<Map>)resultMap.get("Result")).get(0);

        List<Map> lm = (List<Map>)m.get("Utxo");

        BigDecimal total = new BigDecimal("0.0");

        for(int i=0;i<lm.size();i++){
            Map md = lm.get(i);
            BigDecimal v = new BigDecimal((String)md.get("Value"));
            total = total.add(v);
        }

        return JSON.toJSONString(new ReturnMsgEntity().setResult(total.toString()).setStatus(retCodeConfiguration.SUCC()));
    }

    /**
     * get address utxos
     * @param address
     * @return
     */
    public String getUtxos(String address){

        return reqChainData(nodeConfiguration.getUtxoByAddr()+ address);
    }


    /**
     * create did
     * @return
     * @throws Exception
     */
    public String createDid() throws Exception{
        JSONObject result = new JSONObject();
        String privKey = Ela.getPrivateKey();
        String did = Ela.getIdentityIDFromPrivate(privKey);
        result.put("privateKey",privKey);
        result.put("did",did);
        return JSON.toJSONString(new ReturnMsgEntity().setResult(result).setStatus(retCodeConfiguration.SUCC()));
    }

    /**
     * using privateKey sign data
     * @param entity
     * @return
     * @throws Exception
     */
    public String sign(SignDataEntity entity)  throws Exception{
        JSONObject result = new JSONObject();
        String msg = entity.getMsg();
        String privateKey = entity.getPrivateKey();
        ECKey ec = ECKey.fromPrivate(DatatypeConverter.parseHexBinary(privateKey));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.write(msg.getBytes(CHARSET));
        byte[] signature = SignTool.doSign(baos.toByteArray(), DatatypeConverter.parseHexBinary(privateKey));
        byte[] code = new byte[33];
        System.arraycopy(Util.CreateSingleSignatureRedeemScript(ec.getPubBytes(),1), 1,code,0,code.length);
        result.put("msg",DatatypeConverter.printHexBinary(msg.getBytes(CHARSET)));
        result.put("pub",DatatypeConverter.printHexBinary(code));
        result.put("sig",DatatypeConverter.printHexBinary(signature));
        return JSON.toJSONString(new ReturnMsgEntity().setResult(result).setStatus(retCodeConfiguration.SUCC()));
    }

    /**
     * verify if message is signed by a public key
     * @param entity
     * @return
     */
    public String verify(SignDataEntity entity){
        String hexMsg = entity.getMsg();
        String hexSig = entity.getSig();
        String hexPub = entity.getPub();
        byte[] msg = DatatypeConverter.parseHexBinary(hexMsg);
        byte[] sig = DatatypeConverter.parseHexBinary(hexSig);
        byte[] pub = DatatypeConverter.parseHexBinary(hexPub);
        boolean isVerify = ElaSignTool.verify(msg,sig,pub);
        return JSON.toJSONString(new ReturnMsgEntity().setResult(isVerify).setStatus(retCodeConfiguration.SUCC()));
    }

    /**
     * retrive did
     * @param privateKey
     * @return
     * @throws Exception
     */
    public String retriveDid(String privateKey) throws Exception {

        String did = Ela.getIdentityIDFromPrivate(privateKey);

        return JSON.toJSONString(new ReturnMsgEntity().setResult(did).setStatus(retCodeConfiguration.SUCC()));

    }




    /**
     * using http request chain data.
     * @param requestUrl
     * @return
     */
    private String reqChainData(String requestUrl){

        String result = HttpKit.get(requestUrl);

        Map<String,Object>  resultMap = (Map<String,Object>) JSON.parse(result);

        return JSON.toJSONString(new ReturnMsgEntity().setResult(resultMap.get("Result")).setStatus(retCodeConfiguration.SUCC()));

    }
    /**
     * genHdTx info
     * @param hdTxEntity info entity
     * @param utxoList addrs utxo list
     * @return
     * @throws Exception
     */
    private Map<String, Object> genHdTx(HdTxEntity hdTxEntity, List<List<Map>> utxoList) throws Exception {

        String data = hdTxEntity.getMemo();
        HdTxEntity.Output[] outputs = hdTxEntity.getOutputs();
        double smAmt = 0;
        for (int i = 0; i < outputs.length; i++) {
            smAmt += outputs[i].getAmt()/(basicConfiguration.ONE_ELA() * 1.0);
        }
        Map<String, Object> paraListMap = new HashMap<>();
        List txList = new ArrayList<>();
        paraListMap.put("Transactions", txList);
        Map txListMap = new HashMap();
        txList.add(txListMap);
        int index = -1;
        double spendMoney = 0.0;
        boolean hasEnoughFee = false;
        List utxoInputsArray = new ArrayList<>();
        txListMap.put("UTXOInputs", utxoInputsArray);
        for (int j = 0; j < utxoList.size(); j++) {
            List<Map> utxolm = utxoList.get(j);
            String addr = hdTxEntity.getInputs()[j];
            for (int i = 0; i < utxolm.size(); i++) {
                index = i;
                spendMoney += Double.valueOf(utxolm.get(i).get("Value") + "");
                if (Math.round(spendMoney * basicConfiguration.ONE_ELA()) >= Math.round((smAmt + basicConfiguration.FEE()) * basicConfiguration.ONE_ELA())) {
                    hasEnoughFee = true;
                    break;
                }
            }
            for (int i = 0; i <= index; i++) {
                Map<String, Object> utxoInputsDetail = new HashMap<>();
                Map<String, Object> utxoM = utxolm.get(i);
                utxoInputsDetail.put("txid", utxoM.get("Txid"));
                utxoInputsDetail.put("index", utxoM.get("Index"));
                utxoInputsDetail.put("address", addr);
                utxoInputsArray.add(utxoInputsDetail);
            }
            if (hasEnoughFee) {
                break;
            }
        }

        if (!hasEnoughFee) {
            throw new ApiRequestDataException("Not Enough UTXO");
        }
        List utxoOutputsArray = new ArrayList<>();
        txListMap.put("Outputs", utxoOutputsArray);
        for (int i = 0; i < outputs.length; i++) {
            Map<String, Object> utxoOutputsDetail = new HashMap<>();
            utxoOutputsDetail.put("address", outputs[i].getAddr());
            utxoOutputsDetail.put("amount", outputs[i].getAmt());
            utxoOutputsArray.add(utxoOutputsDetail);
        }
        double leftMoney = (spendMoney - (basicConfiguration.FEE() + smAmt));
        if (Math.round(leftMoney * basicConfiguration.ONE_ELA()) > Math.round(basicConfiguration.FEE() * basicConfiguration.ONE_ELA())) {
            Map<String, Object> utxoOutputsDetail = new HashMap<>();
            utxoOutputsDetail.put("address", hdTxEntity.getInputs()[0]);
            utxoOutputsDetail.put("amount", Math.round(leftMoney * basicConfiguration.ONE_ELA()));
            utxoOutputsArray.add(utxoOutputsDetail);
        }

        txListMap.put("Fee",basicConfiguration.FEE() * basicConfiguration.ONE_ELA());
        return paraListMap;
    }

    /**
     * @param result
     * @return
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map> stripUtxo(String result) {

        Map m = JsonUtil.jsonToMap(JSONObject.fromObject(result));
        List<Map> lm = null;
        try {
            lm = ((List<Map>) m.get("Result"));
        } catch (Exception ex) {
            logger.warn(" address has no utxo yet .");
            return null;
        }
        List<Map> l = null;
        if (lm != null) {
            l = (List<Map>) lm.get(0).get("Utxo");
        }
        return l;
    }


    private String getUtxoByAddr(String addr) {

        String result = HttpKit.get(nodeConfiguration.getUtxoByAddr() + addr);

        return result;
    }

    @SuppressWarnings("unchecked")
    public String transfer(TransferParamEntity param) throws Exception {

        List<LinkedHashMap> rcv = (List<LinkedHashMap>) param.getReceiver();
        List<String> addrList = new ArrayList<>();
        List<Double> valList = new ArrayList<>();
        Double totalAmt = 0.0;
        for(int i=0;i<rcv.size();i++){
            Map m = rcv.get(i);
            addrList.add((String)m.get("address"));
            Double tmpAmt = Double.valueOf((String)m.get("amount"));
            valList.add(tmpAmt);
            totalAmt += tmpAmt;
        }

        String senderPrivateKey = param.getSenderPrivateKey();
        String senderAddr = param.getSenderAddr();
        String memo = param.getMemo();

        String response = gen(totalAmt, senderPrivateKey , senderAddr,
                addrList, valList, memo);
        Map<String,Object> rawM = (Map<String, Object>) ((Map<String, Object>) JSON.parse(response)).get("Result");
        String rawTx = (String) rawM.get("rawTx");
        String txHash = (String) rawM.get("txHash");
        logger.info("rawTx:" + rawTx + ", txHash :" + txHash);

        sendTx(rawTx,txHash);
        return JSON.toJSONString(new ReturnMsgEntity().setResult(txHash.toLowerCase()).setStatus(retCodeConfiguration.SUCC()));
    }

    /**
     * send a transaction to blockchain.
     * @param smAmt
     * @param privateKey
     * @param addr
     * @param addrs
     * @param amts
     * @param data
     * @return
     * @throws Exception
     */
    @SuppressWarnings("rawtypes")
    public String gen(double smAmt , String privateKey , String addr ,List<String> addrs , List<Double> amts , String data) throws Exception {

        String utxoStr = getUtxoByAddr(addr);

        List<Map> utxo = stripUtxo(utxoStr);
        if(utxo == null){
            throw new ApiRequestDataException("no UTXO");
        }
        String response = genTx(smAmt, utxo, privateKey, addr, addrs, amts, data);

        return response;
    }

    /**
     * generate raw transaction.
     * @param smAmt the total spend money
     * @param utxolm utxo
     * @param privateKey sender private key
     * @param addr sender public address
     * @param addrs receiver addresses
     * @param amts receiver output money
     * @param data memo data
     * @return
     * @throws Exception
     */
    @SuppressWarnings({ "rawtypes", "unchecked", "static-access" })
    public String genTx(double smAmt , List<Map> utxolm , String privateKey , String addr ,List<String> addrs , List<Double> amts , String data) throws Exception {

        if(addrs == null || addrs.size() == 0) {
            throw new RuntimeException("output can not be blank");
        }

        Map<String,Object> paraListMap = new HashMap<>();
        List txList = new ArrayList<>();
        paraListMap.put("Transactions", txList);
        Map<String,Object> txListMap = new HashMap<>();
        txList.add(txListMap);
        if(!StrKit.isBlank(data)) {
            txListMap.put("Memo", data);
        }

        int index = -1;
        double spendMoney = 0.0;
        boolean hasEnoughFee = false;
        for( int i=0; i<utxolm.size(); i++) {
            index = i;
            spendMoney += Double.valueOf(utxolm.get(i).get("Value")+"");
            if( Math.round(spendMoney * basicConfiguration.ONE_ELA()) >= Math.round((smAmt + basicConfiguration.FEE()) * basicConfiguration.ONE_ELA())) {
                hasEnoughFee = true;
                break;
            }
        }

        if(!hasEnoughFee) {
            return null;
        }

        List utxoInputsArray = new ArrayList<>();
        txListMap.put("UTXOInputs", utxoInputsArray);
        for(int i=0;i<=index;i++) {
            Map<String,Object> utxoInputsDetail = new HashMap<>();
            Map<String,Object> utxoM = utxolm.get(i);
            utxoInputsDetail.put("txid",  utxoM.get("Txid"));
            utxoInputsDetail.put("index",  utxoM.get("Index"));
            utxoInputsDetail.put("privateKey",  privateKey);
            utxoInputsDetail.put("address",  addr);
            utxoInputsArray.add(utxoInputsDetail);
        }
        List utxoOutputsArray = new ArrayList<>();
        txListMap.put("Outputs", utxoOutputsArray);
        for(int i=0;i<addrs.size();i++) {
            Map<String,Object> utxoOutputsDetail = new HashMap<>();
            utxoOutputsDetail.put("address", addrs.get(i));
            utxoOutputsDetail.put("amount", Math.round(amts.get(i) * basicConfiguration.ONE_ELA()));
            utxoOutputsArray.add(utxoOutputsDetail);
        }
        double leftMoney = (spendMoney - (basicConfiguration.FEE() + smAmt));
        if(Math.round(leftMoney * basicConfiguration.ONE_ELA()) > Math.round(basicConfiguration.FEE() * basicConfiguration.ONE_ELA())) {
            Map<String,Object> utxoOutputsDetail = new HashMap<>();
            utxoOutputsDetail.put("address", addr);
            utxoOutputsDetail.put("amount",Math.round(leftMoney * basicConfiguration.ONE_ELA()));
            utxoOutputsArray.add(utxoOutputsDetail);
        }
        JSONObject par = new JSONObject();
        par.accumulateAll(paraListMap);
        logger.info("sending : " + par);
        String rawTx = null ;
        rawTx = ElaKit.genRawTransaction(par);
        logger.info("receiving : " + rawTx);
        return rawTx;
    }

    @SuppressWarnings("static-access")
    public String sendTx(String rawData , String txHash) {
        RawTxEntity entity = new RawTxEntity();
        entity.setData(rawData);
        return sendRawTx(entity);
    }

    /**
     * set did info into memo
     * @param info
     * @return
     * @throws Exception
     */
    public String setDidInfo(DidInfoEntity info) throws Exception{
        String data = null;
        try {
            data = JSON.toJSONString(info.getInfo());
        }catch (Exception ex){
            throw new ApiRequestDataException("DID info must be a json object");
        }
        String privateKey = info.getPrivateKey();
        String recevAddr = didConfiguration.getAddress();
        String fee = didConfiguration.getFee();
        TransferParamEntity transferParamEntity = new TransferParamEntity();
        SignDataEntity signDataEntity = new SignDataEntity();
        signDataEntity.setPrivateKey(privateKey);
        signDataEntity.setMsg(data);
        String response = sign(signDataEntity);
        Map respMap = (Map)JSON.parse(response);
        String rawMemo = JSON.toJSONString(respMap.get("result"));
        logger.debug("rawMemo:{}",rawMemo);
        transferParamEntity.setMemo(rawMemo);
        transferParamEntity.setSenderAddr(Ela.getAddressFromPrivate(privateKey));
        transferParamEntity.setSenderPrivateKey(privateKey);
        List<Map> receiverList = new ArrayList<>();
        Map receivMap = new HashMap();
        receivMap.put("address",recevAddr);
        receivMap.put("amount",fee);
        receiverList.add(receivMap);
        transferParamEntity.setReceiver(receiverList);
        return transfer(transferParamEntity);
    }

    private final static String DID_NO_SUCH_INFO = "No such info";
    /**
     * get did info from memo
     * @param entity
     * @return
     * @throws Exception
     */
    public String getDidInfo(DidInfoEntity entity) throws Exception{
        List<String> txidList = entity.getTxIds();
        if(txidList.size() == 0){
            return JSON.toJSONString(new ReturnMsgEntity().setResult(DID_NO_SUCH_INFO).setStatus(retCodeConfiguration.SUCC()));
        }
        String key = entity.getKey();
        //TODO deal with the same field
        for(int i=0;i<txidList.size();i++){
            String txid = txidList.get(i);
            String txinfo = getTransactionByHash(txid);
            Map txinfoMap = (Map)JSON.parse(txinfo);
            Map resultMap = (Map)txinfoMap.get("result");
            List<Map> attrList = (List)resultMap.get("attributes");
            String hexData = (String)attrList.get(0).get("data");
            Map rawMap = (Map)JSON.parse(new String(DatatypeConverter.parseHexBinary(hexData)));
            SignDataEntity signDataEntity = new SignDataEntity();
            String hexMsg = (String)rawMap.get("msg");
            signDataEntity.setMsg(hexMsg);
            signDataEntity.setSig((String)rawMap.get("sig"));
            signDataEntity.setPub((String)rawMap.get("pub"));
            String verifyResp = verify(signDataEntity);
            Map verifyMap = (Map)JSON.parse(verifyResp);
            Boolean verified = (Boolean)verifyMap.get("result");
            if (!verified){
                continue;
            }else{
                Map rawMsgMap = (Map)JSON.parse(new String(DatatypeConverter.parseHexBinary(hexMsg)));
                Object v = rawMsgMap.get(key);
                if (v == null){
                    continue;
                }
                return JSON.toJSONString(new ReturnMsgEntity().setResult(v).setStatus(retCodeConfiguration.SUCC()));
            }
        }
        return JSON.toJSONString(new ReturnMsgEntity().setResult(DID_NO_SUCH_INFO).setStatus(retCodeConfiguration.SUCC()));
    }
}
