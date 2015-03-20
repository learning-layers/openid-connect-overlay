package org.mitre.openid.connect;


import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.filter.Filter;
import org.springframework.ldap.filter.LikeFilter;

import edu.mit.kit.repository.impl.LdapUserInfoRepository;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.Date;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



import org.mitre.openid.connect.model.DefaultUserInfo;
import org.mitre.openid.connect.model.UserInfo;

import java.util.HashMap;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import javax.annotation.PostConstruct;





@Controller
public class SyncEndpoint {
	
	@Autowired
	private LdapTemplate ldapTemplate;
	
	@Autowired
	private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

	
	public SyncEndpoint(){
	}
	
	
	
	@RequestMapping("/updateuser/new")
	@ResponseBody
	public ResponseEntity<String> createUser(@RequestBody String obj){
		Filter find = new EqualsFilter("uid",obj);
		List<UserInfo> res = ldapTemplate.search("",find.encode(),attributesMapper);
		if(res.size()>1) return new ResponseEntity(HttpStatus.CONFLICT);
		if(res.size()==0) return new ResponseEntity(HttpStatus.NOT_FOUND);
		Map<String,Object> args = createArgs(res.get(0));
		args.put("sub",random());
		String argParams=mapKeys(args);
		String namedParams=mapSqlValues(args);
		if(namedParameterJdbcTemplate.queryForInt("select COUNT(*) from user_info where preferred_username= :preferred_username and email= :email",args)==0){
			namedParameterJdbcTemplate.update("insert into user_info "+ argParams +" values "
					+ namedParams,args);
			return new ResponseEntity(HttpStatus.OK);
			}
		return new ResponseEntity(HttpStatus.BAD_REQUEST);
	}
	
	@RequestMapping("/updateuser/update")
	@ResponseBody
	public ResponseEntity<String> updateUser(@RequestBody String obj){
		Filter find = new EqualsFilter("uid",obj);
		List<UserInfo> res = ldapTemplate.search("",find.encode(),attributesMapper);
		if(res.size()>1) return new ResponseEntity(HttpStatus.CONFLICT);
		if(res.size()==0) return new ResponseEntity(HttpStatus.NOT_FOUND);
		Map<String,Object> args = createArgs(res.get(0));
		String argParams=mapKeys(args);
		String namedParams=mapSqlValues(args);
		int rowcount=namedParameterJdbcTemplate.queryForInt("select COUNT(*) from user_info where preferred_username= :preferred_username",args);
		if(rowcount==1){
			namedParameterJdbcTemplate.update("UPDATE user_info SET "+mapUpdateValues(args)+" WHERE preferred_username = :preferred_username", args);
			return new ResponseEntity(HttpStatus.OK);
		}
		return new ResponseEntity(HttpStatus.BAD_REQUEST);
	}
	
	
	//upon deployment this method is called and syncs the mysql table with the LDAP directory
	@PostConstruct
	public void firstSync(){
		List<String> ldapList = getLdapNames();
		List<String> sqlList = getSqlNames();
		for(String uid: ldapList){
			if(!sqlList.contains(uid)){	
				createUser(uid);
			}
			else{
				updateUser(uid);
			}
		}
		deleteUser();
	}
	
	//Helping methods for queries and argument creation

	public void deleteUser(){
		List<String> x=computeDifferences(getLdapNames(),getSqlNames());
		Map<String,String> a = new HashMap<String,String>();
		for(String s:x){
			a.put("uid",s);
			namedParameterJdbcTemplate.update("DELETE FROM user_info WHERE preferred_username= :uid ",a);
		}
	}
	

	
	public List<String> getLdapNames(){
		List<String> tmp = new ArrayList();
		Filter find = new LikeFilter("objectclass", "pwmUser" );
		List<UserInfo> res = ldapTemplate.search("", find.encode(), uidMapper);
		for(UserInfo s: res) tmp.add(s.getPreferredUsername());
		return tmp;
	}
	
	public UserInfo getLdapPerson(String uid){
		Filter find = new LikeFilter("uid", uid );
		List<UserInfo> res = ldapTemplate.search("", find.encode(), attributesMapper);
		if(res.size()>0) return res.get(0);
		else return null;
	}
	
	public List<String> getSqlNames(){
		List<String> tmp = new ArrayList();
		List<UserInfo> res = namedParameterJdbcTemplate.query("select * from user_info",new HashMap<String,String>(),rowMapper);
		for(UserInfo s: res) tmp.add(s.getPreferredUsername());
		return tmp;
	}
	
	public List<UserInfo> getSqlPersons(){
		List<UserInfo> res = namedParameterJdbcTemplate.query("select * from user_info",new HashMap<String,String>(),rowMapper);
		return res;
	}
	
	
	public List getLdapPersons(){
		Filter find = new LikeFilter("objectclass", "pwmUser" );
		List res = ldapTemplate.search("", find.encode(), attributesMapper);
		return res;

	}
	
	private AttributesMapper uidMapper = new AttributesMapper(){
		@Override
		public Object mapFromAttributes(Attributes attr) throws NamingException{
			
			if(attr.get("uid") != null){
				UserInfo ui=new DefaultUserInfo();
				ui.setPreferredUsername(attr.get("uid").get().toString());
				return ui;
			}
			else return null;
		}
	};
	
	private RowMapper rowMapper = new RowMapper(){
		@Override
		public Object mapRow(ResultSet rs,int rowNum) throws SQLException{
			try{
				UserInfo ui = new DefaultUserInfo();
				if(rs.getString("sub")!=null) ui.setSub(rs.getString("sub"));
				else return null;
				
				if(rs.getString("preferred_username")!=null) ui.setPreferredUsername(rs.getString("preferred_username"));
				if(rs.getString("given_name")!=null) ui.setGivenName(rs.getString("given_name"));
				if(rs.getString("middle_name")!=null) ui.setMiddleName(rs.getString("middle_name"));
				if(rs.getString("family_name") != null) ui.setFamilyName(rs.getString("family_name"));
				if(rs.getString("name")!=null) ui.setName(rs.getString("name"));
				if(rs.getString("email")!=null){
					ui.setEmail(rs.getString("email"));
					if(rs.getInt("email_verified")!=0) ui.setEmailVerified(true);
				}
				if(rs.getString("phone_number")!=null){
					ui.setPhoneNumber(rs.getString("phone_number"));
					ui.setPhoneNumberVerified(true);
				}
				if(rs.getString("nickname")!=null) ui.setNickname(rs.getString("nickname"));
				if(rs.getString("zone_info")!=null) ui.setZoneinfo(rs.getString("zone_info"));
				if(rs.getString("profile")!=null) ui.setProfile(rs.getString("profile"));
				if(rs.getString("gender")!=null) ui.setGender(rs.getString("gender"));
				if(rs.getString("birthdate")!=null) ui.setBirthdate(rs.getString("birthdate"));
				if(rs.getString("website")!=null) ui.setWebsite(rs.getString("website"));
				if(rs.getString("picture")!=null) ui.setPicture(rs.getString("picture"));
				if(rs.getString("updated_time")!=null) ui.setUpdatedTime(rs.getString("updated_time"));
				return ui;
			}
			catch(SQLException e){
				e.printStackTrace(System.err);
			}
			return null;
		}
	};
	
	private AttributesMapper attributesMapper = new AttributesMapper() {
		@Override
		public Object mapFromAttributes(Attributes attr) throws NamingException {

			if (attr.get("uid") == null) {
				return null; // we can't go on if there's no UID to look up
			}
			
			UserInfo ui = new DefaultUserInfo();
			
			// save the UID as the preferred username
			ui.setPreferredUsername(attr.get("uid").get().toString());
						
			// add in the optional fields
			
			// email address
			if (attr.get("mail") != null) {
				ui.setEmail(attr.get("mail").get().toString());
				// if this domain also provisions email addresses, this should be set to true
				ui.setEmailVerified(false);
			}else{
				ui.setEmail(null);
			}
			// phone number
			if (attr.get("telephoneNumber") != null) {
				ui.setPhoneNumber(attr.get("telephoneNumber").get().toString());
				// if this domain also provisions phone numbers, this should be set to true
				ui.setPhoneNumberVerified(false);
			}else{
				ui.setPhoneNumber(null);
			}
			
			// name structure
			
			if (attr.get("givenName") != null) {
				ui.setGivenName(attr.get("givenName").get().toString());
				ui.setName(attr.get("givenName").get().toString());
			}else{
				ui.setGivenName(null);
				ui.setName(null);
			}
			
			if (attr.get("initials") != null) {
				ui.setMiddleName(attr.get("initials").get().toString());
				if(ui.getName()!=null){
					ui.setName(ui.getName() + " " + attr.get("initials").get().toString());
				}
			}else{
				ui.setMiddleName(null);
			}
			
			if (attr.get("sn") != null) {
				ui.setFamilyName(attr.get("sn").get().toString());
				if(ui.getName()!=null){
					ui.setName(ui.getName() + " " + attr.get("sn").get().toString());
				}
			}
			else{
				ui.setFamilyName(null);
			}
			

			
			if (attr.get("labeledURI") != null) {
				ui.setPicture(attr.get("labeledURI").get().toString());
			}
			else{
				ui.setPicture("https://api.learning-layers.eu/profile.png");
			}
			
			if (attr.get("pwmEventLog")!=null){
				String date = convertLogToUpdate(attr.get("pwmEventLog").get().toString());
				ui.setUpdatedTime(date);
			}
			else{
				ui.setUpdatedTime(new Date().toString());
			}
			
			return ui;
		}
	};
	
	
	
	public List<String> computeDifferences(List<String> ldapList,List<String> sqlList){
		List<String> items = new ArrayList<String>();
		for(String s: sqlList){
			if(!ldapList.contains(s)) items.add(s);
		}
		return items;
	}

	
	public String extractDateFromLog(String log){
		if (log!=null){
			Pattern pattern = Pattern.compile("timestamp=\"(\\d*)\" eventCode=\"EventLog_CreateUser\"");
			Matcher matcher2 = pattern.matcher(log);
			String value="";
			while(matcher2.find()){
				value = matcher2.group(1);
			}
			pattern = Pattern.compile("timestamp=\"(\\d*)\" eventCode=\"EventLog_UpdateProfile\"");
			Matcher matcher = pattern.matcher(log);
			while(matcher.find()){
				value = matcher.group(1);
			}
			if(value!=""){
				Date update = new Date(Long.parseLong(value));
				return update.toString();
			}
		}
		return new Date().toString();
	}

	//Creates a HashMap from UserInfo which can be used for named queries
	private Map<String,Object> createArgs(UserInfo curr){
		Map<String,Object> args = new HashMap<String,Object>();
		if(curr.getPreferredUsername()!=null) args.put("preferred_username",curr.getPreferredUsername());	
		if(curr.getGivenName()!=null){
			args.put("name",curr.getGivenName());
			args.put("given_name",curr.getGivenName());
		}else{
			args.put("given_name",null);
		}
		if(curr.getMiddleName()!=null){
			args.put("name",args.get("name")+ " " + curr.getMiddleName());
			args.put("middle_name",curr.getMiddleName());
		}else{
			args.put("middle_name",null);
		}
		if(curr.getFamilyName()!=null){
			args.put("name",args.get("name") +" "+curr.getFamilyName());
			args.put("family_name",curr.getFamilyName());
		}else{
			args.put("family_name",null);
		}
		
		if(curr.getEmail()!=null){
			args.put("email",curr.getEmail());
			args.put("email_verified",1);
		}else{
			args.put("email",null);
		}
		if(curr.getPhoneNumber()!=null){
			args.put("phone_number",curr.getPhoneNumber());
			args.put("phone_number_verified",1);
		}else{
			args.put("phone_number",null);
		}
		if(curr.getNickname()!=null) args.put("nickname",curr.getNickname());
		else args.put("nickname",null);
		if(curr.getProfile()!=null) args.put("profile",curr.getProfile());
		else args.put("profile",null);
		if(curr.getPicture()!=null) args.put("picture",curr.getPicture());
		else args.put("picture",null);
		if(curr.getWebsite()!=null) args.put("website",curr.getWebsite());
		else args.put("website",null);
		if(curr.getGender()!=null) args.put("gender",curr.getGender());
		else args.put("gender",null);
		if(curr.getZoneinfo()!=null) args.put("zone_info",curr.getZoneinfo());
		else args.put("zone_info",null);
		if(curr.getBirthdate()!=null) args.put("birthdate",curr.getBirthdate());
		else args.put("birthdate",null);
		if(curr.getUpdatedTime()!=null) args.put("updated_time",curr.getUpdatedTime());
		else args.put("updated_time",null);
		return args;
	}

	
	private String mapKeys(Map<String,Object> args){
		String argParams="(";
		String[] keys= args.keySet().toArray(new String[args.size()]);
		for(int x=0;x<args.size();x++){
			String s = keys[x];
			argParams += s;
			if(!(x==args.size()-1)) argParams += ",";
		}
		argParams += ")";
		return argParams;
	}
	
	private String mapSqlValues(Map<String,Object> args){
		String argParams="(";
		String[] keys= args.keySet().toArray(new String[args.size()]);
		for(int x=0;x<args.size();x++){
			String s = keys[x];
			argParams += ":"+s;
			if(!(x==args.size()-1)) argParams += ",";
		}
		argParams += ")";
		return argParams;
	}
	
	private String mapUpdateValues(Map<String,Object> args){
		String argParams="";
		String[] keys= args.keySet().toArray(new String[args.size()]);
		for(int x=0;x<args.size();x++){
			String s = keys[x];
			argParams += s+"=:"+s;
			if(!(x==args.size()-1)) argParams += ",";
		}
		return argParams;
	}
	
	public static String random() {
		return UUID.randomUUID().toString();
	}
}
