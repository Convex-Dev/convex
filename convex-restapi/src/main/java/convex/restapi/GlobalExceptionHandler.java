package convex.restapi;

import com.hellokaton.blade.ioc.annotation.Bean;
import com.hellokaton.blade.mvc.handler.DefaultExceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



@Bean
public class GlobalExceptionHandler extends DefaultExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class.getName());

	@Override
	public void handle(Exception e) {
		log.info("default exception handler " + e);
		APIResponse.failBadRequest("error " + e);
	}

}
